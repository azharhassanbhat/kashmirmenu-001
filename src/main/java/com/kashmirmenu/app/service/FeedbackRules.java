package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.exception.BadRequestException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * What a guest may say about a meal, and when. Pure, so the rules are testable.
 *
 * <p>Feedback is for an order that was actually SERVED (a cancelled or unfinished order has nothing to rate),
 * within {@value #WINDOW_DAYS} days of being placed, so a link kept for months cannot be used to rate an old
 * visit. The rating is a whole number from 1 to 5. The comment is optional free text, cleaned and capped.
 */
public final class FeedbackRules {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    public static final int MAX_COMMENT = 500;
    public static final int WINDOW_DAYS = 3;
    private static final Duration WINDOW = Duration.ofDays(WINDOW_DAYS);

    private FeedbackRules() {
    }

    /** A valid rating, and the comment cleaned (null when empty). */
    public record Feedback(int rating, String comment) {
    }

    /**
     * A rating sent as a JSON number, as a whole number. Jackson would otherwise round 2.5 down to 2
     * without saying so, giving the caller a rating they did not send. 4 and 4.0 are both 4; null stays
     * null (so {@link #validate} reports it); anything with a fraction, or too large for an int, is refused.
     */
    public static Integer wholeRating(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new BadRequestException("Please choose a rating from " + MIN_RATING + " to " + MAX_RATING + ".");
        }
    }

    public static Feedback validate(Integer rating, String rawComment) {
        if (rating == null || rating < MIN_RATING || rating > MAX_RATING) {
            throw new BadRequestException("Please choose a rating from " + MIN_RATING + " to " + MAX_RATING + ".");
        }
        String comment = rawComment == null
                ? ""
                : rawComment.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (comment.codePointCount(0, comment.length()) > MAX_COMMENT) {
            throw new BadRequestException("Please keep your comment under " + MAX_COMMENT + " characters.");
        }
        return new Feedback(rating, comment.isEmpty() ? null : comment);
    }

    /** Refuses (400) feedback on an order that was not served, or that was placed too long ago. */
    public static void requireOpen(OrderStatus status, LocalDateTime placedAt, LocalDateTime now) {
        if (status != OrderStatus.SERVED) {
            throw new BadRequestException(status == OrderStatus.CANCELLED
                    ? "This order was cancelled, so there is nothing to rate."
                    : "You can leave feedback once your order has been served.");
        }
        if (placedAt != null && now != null && Duration.between(placedAt, now).compareTo(WINDOW) > 0) {
            throw new BadRequestException("Feedback can only be left within " + WINDOW_DAYS + " days of your visit.");
        }
    }
}
