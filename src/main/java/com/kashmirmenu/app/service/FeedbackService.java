package com.kashmirmenu.app.service;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.response.FeedbackEntryResponse;
import com.kashmirmenu.app.dto.response.FeedbackResponse;
import com.kashmirmenu.app.dto.response.FeedbackSummaryResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.entity.OrderFeedback;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.OrderFeedbackRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Private feedback. A guest, holding only their order's random token, rates it once it has been served; the
 * restaurant's management reads the ratings. The token is the guest's whole credential, exactly as for
 * tracking an order, so it can rate that one order and nothing else.
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;

    private final OrderFeedbackRepository feedback;
    private final OrderRepository orders;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;
    private final NotificationService notificationService;

    /** Saves the guest's rating for an order, or replaces the one they gave before (never a second row). */
    @Transactional
    public FeedbackResponse submit(String publicToken, Integer rating, String comment) {
        Order order = orders.findByPublicToken(publicToken)
                // Deliberately bare: a message naming the token would confirm which values exist.
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));

        FeedbackRules.requireOpen(order.getStatus(), order.getCreatedAt(), businessTime.now());
        FeedbackRules.Feedback valid = FeedbackRules.validate(rating, comment);

        OrderFeedback row = feedback.findByOrderId(order.getId()).orElseGet(() -> {
            OrderFeedback created = new OrderFeedback();
            created.setOrder(order);
            created.setRestaurant(order.getRestaurant());
            return created;
        });
        boolean firstRating = row.getId() == null;
        row.setRating(valid.rating());
        row.setComment(valid.comment());
        try {
            feedback.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // Two first submissions raced; the database's one-per-order rule stopped the second.
            throw new ConflictException("Your feedback is already being saved. Please try again in a moment.");
        }
        if (firstRating) {
            // A changed rating is not news; the first one is. The text is the guest's, so only the number is sent.
            notificationService.createNotification(
                    order.getRestaurant(),
                    order,
                    "New customer review",
                    "Order " + order.getOrderNumber() + " was rated " + valid.rating() + " out of 5.",
                    NotificationType.FEEDBACK_RECEIVED);
        }
        return new FeedbackResponse(valid.rating(), valid.comment());
    }

    /** The signed-in restaurant's feedback, newest first. {@code maxRating} limits to low ratings (null for all). */
    @Transactional(readOnly = true)
    public PageResponse<FeedbackEntryResponse> list(Integer maxRating, int page, int size) {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        PageRequest paging = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));

        Page<OrderFeedback> result = maxRating == null
                ? feedback.findByRestaurantIdOrderByCreatedAtDescIdDesc(restaurantId, paging)
                : feedback.findByRestaurantIdAndRatingLessThanEqualOrderByCreatedAtDescIdDesc(restaurantId, maxRating, paging);

        return PageResponse.<FeedbackEntryResponse>builder()
                .items(result.getContent().stream().map(FeedbackService::toEntry).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .hasPrevious(result.hasPrevious())
                .build();
    }

    @Transactional(readOnly = true)
    public FeedbackSummaryResponse summary(Integer requestedDays) {
        int days = requestedDays == null ? DEFAULT_DAYS : Math.min(Math.max(1, requestedDays), MAX_DAYS);
        LocalDateTime since = businessTime.now().minusDays(days);

        long[] counts = new long[FeedbackRules.MAX_RATING];
        for (Object[] row : feedback.countsByRating(currentUserService.getCurrentRestaurantId(), since)) {
            int stars = ((Number) row[0]).intValue();
            if (stars >= FeedbackRules.MIN_RATING && stars <= FeedbackRules.MAX_RATING) {
                counts[stars - 1] = ((Number) row[1]).longValue();
            }
        }
        FeedbackStats.Summary s = FeedbackStats.fromCounts(counts);
        return new FeedbackSummaryResponse(s.count(), s.average(), s.distribution(), s.lowCount(), s.lowPercent(), days);
    }

    private static FeedbackEntryResponse toEntry(OrderFeedback f) {
        Order order = f.getOrder();
        return new FeedbackEntryResponse(
                f.getId(),
                order.getOrderNumber(),
                order.getRestaurantTable() == null ? null : order.getRestaurantTable().getTableName(),
                f.getRating(),
                f.getComment(),
                f.getCreatedAt());
    }
}
