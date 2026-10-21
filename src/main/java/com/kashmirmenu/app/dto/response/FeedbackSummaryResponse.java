package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param average      to one decimal place; null when nobody has rated yet (never 0)
 * @param distribution how many ratings of 1, 2, 3, 4 and 5 stars, in that order
 * @param days         the period these figures cover, ending now
 */
public record FeedbackSummaryResponse(
        long count,
        BigDecimal average,
        List<Long> distribution,
        long lowCount,
        int lowPercent,
        int days) {
}
