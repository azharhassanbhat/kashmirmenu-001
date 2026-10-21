package com.kashmirmenu.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns "how many 1-star, 2-star... ratings" into the figures an owner reads. Pure, so the arithmetic is
 * testable. With no ratings the average is null, never 0: a restaurant nobody has rated is not a restaurant
 * rated zero.
 */
public final class FeedbackStats {

    /** Ratings of this or below are "low": the ones an owner wants to see first. */
    public static final int LOW_AT_OR_BELOW = 2;

    private FeedbackStats() {
    }

    /**
     * @param distribution how many ratings of 1, 2, 3, 4 and 5 stars, in that order (index 0 is one star)
     * @param average      to one decimal place; null when there are no ratings
     * @param lowPercent   share of ratings that are low, rounded to a whole percent; 0 when there are none
     */
    public record Summary(long count, BigDecimal average, List<Long> distribution, long lowCount, int lowPercent) {
    }

    /** @param countsByRating five counts for 1 to 5 stars; anything missing or negative counts as zero */
    public static Summary fromCounts(long[] countsByRating) {
        long[] c = new long[FeedbackRules.MAX_RATING];
        if (countsByRating != null) {
            for (int i = 0; i < Math.min(countsByRating.length, c.length); i++) {
                c[i] = Math.max(0, countsByRating[i]);
            }
        }
        long total = 0, weighted = 0, low = 0;
        List<Long> distribution = new ArrayList<>();
        for (int i = 0; i < c.length; i++) {
            int stars = i + 1;
            total += c[i];
            weighted += stars * c[i];
            if (stars <= LOW_AT_OR_BELOW) {
                low += c[i];
            }
            distribution.add(c[i]);
        }
        BigDecimal average = total == 0
                ? null
                : BigDecimal.valueOf(weighted).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        int lowPercent = total == 0 ? 0 : (int) Math.round(low * 100.0 / total);
        return new Summary(total, average, List.copyOf(distribution), low, lowPercent);
    }
}
