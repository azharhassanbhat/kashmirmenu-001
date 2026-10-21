package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.Detail;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.Overview;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.Summary;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.NotificationService;
import com.kashmirmenu.app.util.AfterCommit;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Platform-level restaurant administration: the registration queue and the approve / reject /
 * suspend / reactivate decisions. Callers are authorised twice (the filter chain and
 * {@code @PreAuthorize} on the controller); nothing here trusts either.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PlatformRestaurantService {

    /** Sortable columns, whitelisted so a client cannot sort by (or probe) arbitrary properties. */
    static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "name", "name",
            "status", "approvalStatus");

    private static final int MAX_PAGE_SIZE = 100;

    /** notifications.message is VARCHAR(500). */
    private static final int NOTIFICATION_MESSAGE_MAX = 500;

    private final RestaurantRepository restaurantRepository;
    private final RestaurantTableRepository tableRepository;
    private final MenuItemRepository menuItemRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final BusinessTime businessTime;

    @Transactional(readOnly = true)
    public Overview overview() {

        long pending = restaurantRepository.countByApprovalStatus(RestaurantStatus.PENDING);
        long approved = restaurantRepository.countByApprovalStatus(RestaurantStatus.APPROVED);
        long rejected = restaurantRepository.countByApprovalStatus(RestaurantStatus.REJECTED);
        long suspended = restaurantRepository.countByApprovalStatus(RestaurantStatus.SUSPENDED);

        return new Overview(pending + approved + rejected + suspended,
                pending, approved, rejected, suspended);
    }

    @Transactional(readOnly = true)
    public PageResponse<Summary> search(
            String q, RestaurantStatus status, int page, int size, String sortBy, String direction) {

        String sortProperty = SORT_FIELDS.get(sortBy);
        if (sortProperty == null) {
            throw new BadRequestException("Cannot sort by '" + sortBy + "'.");
        }

        Sort sort = "asc".equalsIgnoreCase(direction)
                ? Sort.by(sortProperty).ascending()
                : Sort.by(sortProperty).descending();
        // Stable order for rows that tie on the sort column, so paging never repeats or skips a row.
        sort = sort.and(Sort.by("id").descending());

        PageRequest pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE), sort);

        Page<Restaurant> result = restaurantRepository.findAll(filter(q, status), pageable);

        return PageResponse.<Summary>builder()
                .items(result.getContent().stream().map(PlatformRestaurantService::toSummary).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .hasPrevious(result.hasPrevious())
                .build();
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        return toDetail(find(id));
    }

    public Detail approve(Long id) {

        Restaurant restaurant = lock(id);
        RestaurantLifecycle.requireApprove(restaurant.getApprovalStatus());
        boolean wasRejected = restaurant.getApprovalStatus() == RestaurantStatus.REJECTED;

        decide(restaurant, RestaurantStatus.APPROVED, null);

        audit(AuditAction.RESTAURANT_APPROVED, id,
                wasRejected ? "Approved after an earlier rejection" : "Approved");
        notify(restaurant, NotificationType.RESTAURANT_APPROVED, "Restaurant approved",
                "Your restaurant has been approved. You can now manage your menu, tables and orders.");

        log.info("Restaurant {} approved", id);
        return toDetail(restaurant);
    }

    public Detail reject(Long id, String reason) {

        Restaurant restaurant = lock(id);
        RestaurantLifecycle.requireReject(restaurant.getApprovalStatus());

        decide(restaurant, RestaurantStatus.REJECTED, reason.trim());

        audit(AuditAction.RESTAURANT_REJECTED, id, "Rejected: " + reason.trim());
        notify(restaurant, NotificationType.RESTAURANT_REJECTED, "Registration rejected",
                "Your restaurant registration was not approved. Reason: " + reason.trim());

        log.info("Restaurant {} rejected", id);
        return toDetail(restaurant);
    }

    public Detail suspend(Long id, String reason) {

        Restaurant restaurant = lock(id);
        RestaurantLifecycle.requireSuspend(restaurant.getApprovalStatus());

        decide(restaurant, RestaurantStatus.SUSPENDED, reason.trim());

        audit(AuditAction.RESTAURANT_SUSPENDED, id, "Suspended: " + reason.trim());
        notify(restaurant, NotificationType.RESTAURANT_SUSPENDED, "Restaurant suspended",
                "Your restaurant has been suspended. Reason: " + reason.trim());

        log.info("Restaurant {} suspended", id);
        return toDetail(restaurant);
    }

    public Detail reactivate(Long id) {

        Restaurant restaurant = lock(id);
        RestaurantLifecycle.requireReactivate(restaurant.getApprovalStatus());

        decide(restaurant, RestaurantStatus.APPROVED, null);

        audit(AuditAction.RESTAURANT_REACTIVATED, id, "Reactivated");
        notify(restaurant, NotificationType.RESTAURANT_REACTIVATED, "Restaurant reactivated",
                "Your restaurant has been reactivated and can operate again.");

        log.info("Restaurant {} reactivated", id);
        return toDetail(restaurant);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Applies a decision: new status, who and when, and the one reason that belongs to it. The
     * other reason is cleared so an owner is never shown a stale explanation; the audit log keeps
     * every earlier one.
     */
    private void decide(Restaurant restaurant, RestaurantStatus status, String reason) {

        User actor = currentUserService.getCurrentUser();

        restaurant.setApprovalStatus(status);
        restaurant.setReviewedBy(actor);
        restaurant.setReviewedAt(businessTime.now());
        restaurant.setRejectionReason(status == RestaurantStatus.REJECTED ? reason : null);
        restaurant.setSuspensionReason(status == RestaurantStatus.SUSPENDED ? reason : null);

        restaurantRepository.save(restaurant);
    }

    private void notify(Restaurant restaurant, NotificationType type, String title, String message) {
        // Same transaction as the decision on purpose: both are recorded or neither is. The message
        // carries the platform owner's reason (up to 500 characters) after a fixed lead-in, so it is
        // cut to the column: an over-long notification used to fail the whole decision with a 500.
        notificationService.createNotification(restaurant, null, title, fit(message, NOTIFICATION_MESSAGE_MAX), type);
    }

    /**
     * Audit lines are written only after the decision has committed. Written earlier, they are
     * committed in their own transaction and a decision that then fails would still be on record.
     */
    private void audit(AuditAction action, Long restaurantId, String detail) {
        AfterCommit.run(() -> auditService.record(action, "RESTAURANT", restaurantId, detail));
    }

    private static String fit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }

    private Restaurant find(Long id) {
        return restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found."));
    }

    private Restaurant lock(Long id) {
        return restaurantRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found."));
    }

    private static Specification<Restaurant> filter(String q, RestaurantStatus status) {

        return (root, query, cb) -> {

            // Owner is read for every row, so fetch it in the same query (a plain join in the count query).
            boolean countQuery = query != null
                    && (query.getResultType() == Long.class || query.getResultType() == long.class);
            @SuppressWarnings("unchecked")
            Join<Restaurant, User> owner = countQuery
                    ? root.join("owner", JoinType.LEFT)
                    : (Join<Restaurant, User>) (Object) root.fetch("owner", JoinType.LEFT);

            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("approvalStatus"), status));
            }

            if (q != null && !q.isBlank()) {
                String like = "%" + escapeLike(q.trim().toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.lower(root.get("city")), like, '\\'),
                        cb.like(cb.lower(owner.get("name")), like, '\\'),
                        cb.like(cb.lower(owner.get("email")), like, '\\')));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Keeps % and _ typed by the caller literal rather than letting them act as wildcards. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String reasonFor(Restaurant r) {
        return switch (r.getApprovalStatus()) {
            case REJECTED -> r.getRejectionReason();
            case SUSPENDED -> r.getSuspensionReason();
            default -> null;
        };
    }

    static Summary toSummary(Restaurant r) {
        User owner = r.getOwner();
        return new Summary(
                r.getId(), r.getName(),
                owner == null ? null : owner.getName(),
                owner == null ? null : owner.getEmail(),
                r.getEmail(), r.getPhone(), r.getCity(), r.getState(), r.getCountry(),
                r.getCreatedAt(), r.getApprovalStatus(), reasonFor(r), r.getReviewedAt());
    }

    private Detail toDetail(Restaurant r) {
        User owner = r.getOwner();
        User reviewer = r.getReviewedBy();
        return new Detail(
                r.getId(), r.getName(), r.getSlug(),
                owner == null ? null : owner.getName(),
                owner == null ? null : owner.getEmail(),
                null,
                r.getEmail(), r.getPhone(), r.getAddress(), r.getCity(), r.getState(), r.getCountry(),
                r.getCreatedAt(), r.getApprovalStatus(), reasonFor(r), r.getReviewedAt(),
                reviewer == null ? null : reviewer.getName(),
                r.getRejectionReason(), r.getSuspensionReason(),
                tableRepository.countByRestaurantId(r.getId()),
                menuItemRepository.countByRestaurantId(r.getId()),
                List.of());
    }
}
