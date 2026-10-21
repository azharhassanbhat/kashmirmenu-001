package com.kashmirmenu.app.service;

import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.request.CreateServiceRequestRequest;
import com.kashmirmenu.app.dto.response.PublicServiceRequestResponse;
import com.kashmirmenu.app.dto.response.ServiceRequestResponse;
import com.kashmirmenu.app.dto.websocket.NotificationMessage;
import com.kashmirmenu.app.dto.websocket.NotificationPublisher;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.entity.ServiceRequest;
import com.kashmirmenu.app.entity.ServiceRequestEvent;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.metrics.ServiceRequestMetrics;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.repository.ServiceRequestEventRepository;
import com.kashmirmenu.app.repository.ServiceRequestRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Table-side service requests: guests raise them from their phone, staff work them from a board.
 *
 * <p>Guarantees, and where each is enforced:
 * <ul>
 *   <li><b>No request is lost.</b> It is saved in the same transaction that answers the guest, and
 *       the staff push goes out only after that transaction commits.</li>
 *   <li><b>Nothing completes itself.</b> Only {@link #updateStatus} moves a request on, and only
 *       along {@link ServiceRequestStatus#canMoveTo}; PENDING cannot jump to COMPLETED.</li>
 *   <li><b>Repeat taps make one request.</b> Checked here under the table's row lock, and backed
 *       by a unique generated column in the database (V34).</li>
 *   <li><b>Tenant isolation.</b> Staff lookups always include the caller's restaurant id, and an id
 *       from another restaurant answers "not found", exactly like a missing one.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ServiceRequestService {

    /** A guest can have at most this many free-text requests open at once. */
    static final int MAX_OPEN_OTHER = 3;
    /** And at most this many open requests of any kind per visit, as a flood limit. */
    static final int MAX_OPEN_PER_VISIT = 8;
    /** How long finished requests stay on the staff board. */
    static final Duration FINISHED_VISIBLE_FOR = Duration.ofHours(2);

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceRequestRepository requests;
    private final ServiceRequestEventRepository events;
    private final RestaurantTableRepository tables;
    private final TableSessionService tableSessionService;
    private final NotificationService notificationService;
    private final NotificationPublisher notificationPublisher;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;
    private final ServiceRequestMetrics metrics;

    // ------------------------------------------------------------------ guests

    /**
     * Raises a request as the guest the token belongs to. If the guest (or anyone at the table) has
     * already asked for the same thing and it is still open, that request is returned with
     * {@code alreadyRequested = true} instead of making a second one.
     */
    @Transactional
    public PublicServiceRequestResponse submit(String qr, String token, CreateServiceRequestRequest body) {
        // Table row first, like every cart and order operation: that is the documented lock order,
        // and it is what makes the duplicate check below safe against two simultaneous taps.
        RestaurantTable table = tables.findByQrIdentifierForUpdate(qr)
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> Boolean.TRUE.equals(t.getRestaurant().getActive()))
                .orElseThrow(() -> new ResourceNotFoundException("This table is not available."));

        DinerSession diner = tableSessionService.resolve(qr, token);
        TableSession visit = diner.getTableSession();
        ServiceRequestType type = body.type();
        String note = ServiceRequestNotes.clean(body.note());

        if (type == ServiceRequestType.OTHER && note == null) {
            throw new BadRequestException("Please tell us what you need.");
        }

        Set<ServiceRequestStatus> open = ServiceRequestStatus.OPEN;

        if (!type.allowsSeveralOpen()) {
            var existing = requests.findFirstByTableSessionIdAndTypeAndStatusIn(visit.getId(), type, open);
            if (existing.isPresent()) {
                metrics.duplicateIgnored(type);
                return toPublic(existing.get(), diner, true);
            }
        } else if (requests.countByTableSessionIdAndTypeAndStatusIn(visit.getId(), type, open) >= MAX_OPEN_OTHER) {
            throw new ConflictException(
                    "You already have several requests waiting. Please wait for staff to respond.");
        }

        if (requests.countByTableSessionIdAndStatusIn(visit.getId(), open) >= MAX_OPEN_PER_VISIT) {
            throw new ConflictException(
                    "There are already several requests waiting at your table. Please wait for staff to respond.");
        }

        ServiceRequest request = new ServiceRequest();
        request.setRequestNumber(newRequestNumber());
        request.setRestaurant(table.getRestaurant());
        request.setRestaurantTable(table);
        request.setTableSession(visit);
        request.setDinerSession(diner);
        request.setType(type);
        request.setStatus(ServiceRequestStatus.PENDING);
        request.setNote(note);
        request = requests.save(request);

        recordEvent(request, null, ServiceRequestStatus.PENDING, diner.getDisplayName());
        metrics.created(type);

        // Persisted notification: it feeds the bell and, after commit, the staff websocket topic.
        String where = tableLabel(table);
        notificationService.createNotification(
                table.getRestaurant(),
                null,
                clip("New request · " + where, 150),
                clip(type.label() + (note == null ? "" : " — " + note), 500),
                NotificationType.SERVICE_REQUEST_CREATED,
                NotificationEventKind.forNewRequest(type));

        return toPublic(request, diner, false);
    }

    /** Every request at the guest's current visit, newest first. */
    @Transactional(readOnly = true)
    public List<PublicServiceRequestResponse> listForGuest(String qr, String token) {
        DinerSession diner = tableSessionService.resolve(qr, token);
        return requests.findForVisit(diner.getTableSession().getId()).stream()
                .map(r -> toPublic(r, diner, false))
                .toList();
    }

    /**
     * A guest withdrawing their own request. Only while nobody has picked it up: once staff have
     * acknowledged it, cancelling is a conversation, not a tap.
     */
    @Transactional
    public PublicServiceRequestResponse cancelForGuest(String qr, String token, String requestNumber) {
        DinerSession diner = tableSessionService.resolve(qr, token);
        ServiceRequest found = requests.findByNumberInVisit(requestNumber, diner.getTableSession().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Request not found."));

        // Not yours is indistinguishable from not there, so numbers cannot be probed.
        if (found.getDinerSession() == null || !found.getDinerSession().getId().equals(diner.getId())) {
            throw new ResourceNotFoundException("Request not found.");
        }

        ServiceRequest request = requests.lockByIdAndRestaurantId(found.getId(), found.getRestaurant().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Request not found."));

        if (request.getStatus() == ServiceRequestStatus.CANCELLED) {
            return toPublic(request, diner, false); // retried cancel: already done
        }
        if (request.getStatus() != ServiceRequestStatus.PENDING) {
            throw new ConflictException("A member of staff is already handling this request.");
        }

        applyStatus(request, ServiceRequestStatus.CANCELLED, diner.getDisplayName());
        publishUpdate(request);
        return toPublic(request, diner, false);
    }

    // ------------------------------------------------------------------- staff

    /**
     * The staff board: every open request (oldest first, so the longest wait is on top) plus
     * anything finished in the last two hours.
     */
    @Transactional(readOnly = true)
    public List<ServiceRequestResponse> board() {
        Long restaurantId = requireRestaurantId();
        LocalDateTime now = businessTime.now();

        List<ServiceRequest> all = new java.util.ArrayList<>(
                requests.findForBoard(restaurantId, ServiceRequestStatus.OPEN));
        all.addAll(requests.findFinishedSince(
                restaurantId,
                EnumSet.of(ServiceRequestStatus.COMPLETED, ServiceRequestStatus.CANCELLED),
                now.minus(FINISHED_VISIBLE_FOR)));

        return all.stream().map(r -> toStaff(r, now, false)).toList();
    }

    @Transactional(readOnly = true)
    public ServiceRequestResponse get(Long id) {
        Long restaurantId = requireRestaurantId();
        ServiceRequest request = requests.findByIdAndRestaurantId(id, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found."));
        return toStaff(request, businessTime.now(), true);
    }

    /**
     * Moves a request along its lifecycle. Acknowledging a request also assigns it to the member of
     * staff who did, so the board shows who is on it.
     */
    @Transactional
    public ServiceRequestResponse updateStatus(Long id, ServiceRequestStatus next) {
        Long restaurantId = requireRestaurantId();
        ServiceRequest request = requests.lockByIdAndRestaurantId(id, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found."));

        ServiceRequestStatus current = request.getStatus();
        if (current == next) {
            return toStaff(request, businessTime.now(), true); // retried tap: already there
        }
        if (!current.canMoveTo(next)) {
            throw new ConflictException(
                    "This request is " + pretty(current) + " and cannot be changed to " + pretty(next) + ".");
        }

        User actor = currentUserService.getCurrentUser();
        if (next == ServiceRequestStatus.ACKNOWLEDGED && request.getAssignedTo() == null) {
            request.setAssignedTo(actor);
        }

        applyStatus(request, next, actor.getEmail());
        publishUpdate(request);
        return toStaff(request, businessTime.now(), true);
    }

    // ---------------------------------------------------------------- internals

    private void applyStatus(ServiceRequest request, ServiceRequestStatus next, String actorLabel) {
        ServiceRequestStatus previous = request.getStatus();
        LocalDateTime now = businessTime.now();

        request.setStatus(next);
        if (next == ServiceRequestStatus.ACKNOWLEDGED && request.getAcknowledgedAt() == null) {
            request.setAcknowledgedAt(now);
            metrics.acknowledgedAfter(Duration.between(request.getCreatedAt(), now));
        }
        if (next == ServiceRequestStatus.COMPLETED) {
            request.setCompletedAt(now);
            if (request.getAcknowledgedAt() == null) {
                request.setAcknowledgedAt(now);
            }
        }
        requests.save(request);
        recordEvent(request, previous, next, actorLabel);
        metrics.transitioned(next);
    }

    private void recordEvent(ServiceRequest request, ServiceRequestStatus from,
                             ServiceRequestStatus to, String actorLabel) {
        ServiceRequestEvent event = new ServiceRequestEvent();
        event.setServiceRequest(request);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setActorLabel(clip(actorLabel == null ? "unknown" : actorLabel, 120));
        events.save(event);
    }

    /**
     * Tells every open staff board that something changed. Transient (not saved as a notification,
     * so the bell is not flooded by every status tap) and sent only after the transaction commits,
     * so a board never refreshes for a change that then rolls back.
     */
    private void publishUpdate(ServiceRequest request) {
        NotificationMessage message = NotificationMessage.builder()
                .notificationId(null)
                .restaurantId(request.getRestaurant().getId())
                .orderId(null)
                .title(clip("Request updated · " + tableLabel(request.getRestaurantTable()), 150))
                .message(clip(request.getType().label() + ": " + pretty(request.getStatus()), 500))
                .notificationType(NotificationType.SERVICE_REQUEST_UPDATED)
                .eventKind(NotificationEventKind.TABLE_REQUEST_UPDATE)
                .createdAt(businessTime.now().toString())
                .build();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationPublisher.publish(message);
                }
            });
        } else {
            notificationPublisher.publish(message);
        }
    }

    private Long requireRestaurantId() {
        Long id = currentUserService.getCurrentRestaurantId();
        if (id == null) {
            throw new ResourceNotFoundException("Request not found.");
        }
        return id;
    }

    private String newRequestNumber() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("SR-");
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (!requests.existsByRequestNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a request number.");
    }

    private static String tableLabel(RestaurantTable table) {
        String name = table.getTableName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return table.getTableCode() == null ? "Table" : table.getTableCode();
    }

    private static String pretty(ServiceRequestStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private PublicServiceRequestResponse toPublic(ServiceRequest r, DinerSession viewer, boolean alreadyRequested) {
        boolean mine = r.getDinerSession() != null && r.getDinerSession().getId().equals(viewer.getId());
        return new PublicServiceRequestResponse(
                r.getRequestNumber(),
                r.getType(),
                r.getType().label(),
                r.getStatus(),
                // Another guest's note may be private ("my child is allergic to..."), so it is theirs alone.
                mine ? r.getNote() : null,
                mine,
                alreadyRequested,
                r.getCreatedAt(),
                r.getAcknowledgedAt(),
                r.getCompletedAt());
    }

    private ServiceRequestResponse toStaff(ServiceRequest r, LocalDateTime now, boolean withHistory) {
        LocalDateTime end = r.getStatus().isOpen()
                ? now
                : (r.getCompletedAt() != null ? r.getCompletedAt() : r.getUpdatedAt());
        long waiting = Math.max(0, Duration.between(r.getCreatedAt(), end == null ? now : end).getSeconds());

        List<ServiceRequestResponse.Event> history = withHistory
                ? events.findByServiceRequestIdOrderByCreatedAtAscIdAsc(r.getId()).stream()
                        .map(e -> new ServiceRequestResponse.Event(
                                e.getFromStatus(), e.getToStatus(), e.getActorLabel(), e.getCreatedAt()))
                        .toList()
                : List.of();

        User assignee = r.getAssignedTo();
        return new ServiceRequestResponse(
                r.getId(),
                r.getRequestNumber(),
                r.getType(),
                r.getType().label(),
                r.getStatus(),
                r.getType().urgent(),
                r.getNote(),
                r.getRestaurantTable().getId(),
                tableLabel(r.getRestaurantTable()),
                r.getDinerSession() == null ? null : r.getDinerSession().getDisplayName(),
                assignee == null ? null : assignee.getId(),
                assignee == null ? null : assignee.getName(),
                r.getCreatedAt(),
                r.getAcknowledgedAt(),
                r.getCompletedAt(),
                waiting,
                history);
    }
}
