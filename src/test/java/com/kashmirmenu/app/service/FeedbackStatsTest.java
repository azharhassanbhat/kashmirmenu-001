package com.kashmirmenu.app.service;

import com.kashmirmenu.app.service.FeedbackStats.Summary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The figures an owner reads from a pile of ratings. */
class FeedbackStatsTest {

    @Test
    void noRatingsMeansNoAverageNotAZeroAverage() {
        Summary s = FeedbackStats.fromCounts(new long[]{0, 0, 0, 0, 0});
        assertEquals(0, s.count());
        assertNull(s.average());
        assertEquals(0, s.lowPercent());
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), s.distribution());
    }

    @Test
    void theAverageIsWeightedByHowManyGaveEachRating() {
        // 2 x 5, 1 x 3, 1 x 1 = 14 / 4 = 3.5
        Summary s = FeedbackStats.fromCounts(new long[]{1, 0, 1, 0, 2});
        assertEquals(4, s.count());
        assertEquals(new BigDecimal("3.5"), s.average());
    }

    @Test
    void theAverageIsRoundedToOneDecimalHalfUp() {
        assertEquals(new BigDecimal("4.7"), FeedbackStats.fromCounts(new long[]{0, 0, 0, 1, 2}).average());   // 14/3 = 4.666
        assertEquals(new BigDecimal("1.3"), FeedbackStats.fromCounts(new long[]{2, 1, 0, 0, 0}).average());   // 4/3 = 1.333
        assertEquals(new BigDecimal("3.5"), FeedbackStats.fromCounts(new long[]{0, 0, 1, 1, 0}).average());   // exactly 3.5
        assertEquals(new BigDecimal("4.0"), FeedbackStats.fromCounts(new long[]{0, 0, 0, 5, 0}).average());
    }

    @Test
    void theDistributionIsOneToFiveStarsInOrder() {
        Summary s = FeedbackStats.fromCounts(new long[]{5, 4, 3, 2, 1});
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), s.distribution());
        assertEquals(15, s.count());
    }

    @Test
    void lowRatingsAreOneAndTwoStarsAndTheirShareIsWholePercent() {
        Summary s = FeedbackStats.fromCounts(new long[]{1, 1, 0, 0, 2});   // 2 of 4 are low
        assertEquals(2, s.lowCount());
        assertEquals(50, s.lowPercent());
        assertEquals(33, FeedbackStats.fromCounts(new long[]{1, 0, 0, 0, 2}).lowPercent());   // 1 of 3
        assertEquals(67, FeedbackStats.fromCounts(new long[]{1, 1, 0, 0, 1}).lowPercent());   // 2 of 3
        assertEquals(0, FeedbackStats.fromCounts(new long[]{0, 0, 3, 4, 5}).lowPercent());
        assertEquals(100, FeedbackStats.fromCounts(new long[]{3, 4, 0, 0, 0}).lowPercent());
    }

    @Test
    void shortOrMissingOrNegativeInputIsHandledNotFatal() {
        assertEquals(0, FeedbackStats.fromCounts(null).count());
        Summary shortInput = FeedbackStats.fromCounts(new long[]{0, 0, 2});
        assertEquals(2, shortInput.count());
        assertEquals(new BigDecimal("3.0"), shortInput.average());
        assertEquals(1, FeedbackStats.fromCounts(new long[]{-5, 0, 0, 0, 1}).count());
        assertEquals(5, FeedbackStats.fromCounts(new long[]{0, 0, 0, 0, 1, 99, 99}).distribution().size() == 5 ? 5 : 0);
    }

    @Test
    void theDistributionCannotBeChangedByTheCaller() {
        Summary s = FeedbackStats.fromCounts(new long[]{1, 1, 1, 1, 1});
        assertThrows(UnsupportedOperationException.class, () -> s.distribution().add(9L));
    }
}
