package com.kashmirmenu.app.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodResolverTest {

    // Tuesday 13 October 2026, 14:30 business time.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 30);

    private static LocalDateTime at(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute);
    }

    @Test
    void today_isComparedWithYesterdayUpToTheSameTime() {
        ResolvedPeriod p = PeriodResolver.resolve(PeriodPreset.TODAY, null, null, NOW);
        assertEquals(at(10, 13, 0, 0), p.current().start());
        assertEquals(at(10, 14, 0, 0), p.current().endExclusive());
        // Not all of yesterday: that would make an unfinished day look like a collapse.
        assertEquals(at(10, 12, 0, 0), p.previous().start());
        assertEquals(at(10, 12, 14, 30), p.previous().endExclusive());
    }

    @Test
    void yesterday_isComparedWithTheFullDayBefore() {
        ResolvedPeriod p = PeriodResolver.resolve(PeriodPreset.YESTERDAY, null, null, NOW);
        assertEquals(at(10, 12, 0, 0), p.current().start());
        assertEquals(at(10, 13, 0, 0), p.current().endExclusive());
        assertEquals(at(10, 11, 0, 0), p.previous().start());
        assertEquals(at(10, 12, 0, 0), p.previous().endExclusive());
    }

    @Test
    void last7Days_includesToday_andComparesTheSameElapsedSlice() {
        ResolvedPeriod p = PeriodResolver.resolve(PeriodPreset.LAST_7_DAYS, null, null, NOW);
        assertEquals(at(10, 7, 0, 0), p.current().start());
        assertEquals(at(10, 14, 0, 0), p.current().endExclusive());
        assertEquals(at(9, 30, 0, 0), p.previous().start());
        // 6 days 14h30m elapsed -> the previous window is exactly as long.
        assertEquals(p.previous().length(), java.time.Duration.between(at(10, 7, 0, 0), NOW));
    }

    @Test
    void thisMonth_isComparedWithTheSameElapsedTimeOfLastMonth() {
        ResolvedPeriod p = PeriodResolver.resolve(PeriodPreset.THIS_MONTH, null, null, NOW);
        assertEquals(at(10, 1, 0, 0), p.current().start());
        assertEquals(at(9, 1, 0, 0), p.previous().start());
        assertEquals(at(9, 13, 14, 30), p.previous().endExclusive());
    }

    @Test
    void thisMonth_neverSpillsIntoTheCurrentMonthWhenLastMonthIsShorter() {
        // 31 October: October has run longer than September is long.
        LocalDateTime endOfOctober = LocalDateTime.of(2026, 10, 31, 23, 0);
        ResolvedPeriod p = PeriodResolver.resolve(PeriodPreset.THIS_MONTH, null, null, endOfOctober);
        assertEquals(at(10, 1, 0, 0), p.previous().endExclusive());
    }

    @Test
    void lastMonth_isComparedWithTheWholeMonthBeforeIt() {
        ResolvedPeriod p = PeriodResolver.resolve(PeriodPreset.LAST_MONTH, null, null, NOW);
        assertEquals(at(9, 1, 0, 0), p.current().start());
        assertEquals(at(10, 1, 0, 0), p.current().endExclusive());
        assertEquals(at(8, 1, 0, 0), p.previous().start());
        assertEquals(at(9, 1, 0, 0), p.previous().endExclusive());
    }

    @Test
    void custom_pastRange_comparesWithTheEqualLengthRangeBefore() {
        ResolvedPeriod p = PeriodResolver.resolve(
                PeriodPreset.CUSTOM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), NOW);
        assertEquals(at(10, 1, 0, 0), p.current().start());
        assertEquals(at(10, 8, 0, 0), p.current().endExclusive());
        assertEquals(at(9, 24, 0, 0), p.previous().start());
        assertEquals(at(10, 1, 0, 0), p.previous().endExclusive());
    }

    @Test
    void custom_isValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> PeriodResolver.resolve(PeriodPreset.CUSTOM, null, LocalDate.of(2026, 10, 7), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PeriodResolver.resolve(PeriodPreset.CUSTOM,
                        LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PeriodResolver.resolve(PeriodPreset.CUSTOM,
                        LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 7), NOW));
    }

    @Test
    void dayBoundariesAreHalfOpen() {
        DateRange today = PeriodResolver.resolve(PeriodPreset.TODAY, null, null, NOW).current();
        assertTrue(today.contains(at(10, 13, 0, 0)));
        assertTrue(today.contains(LocalDateTime.of(2026, 10, 13, 23, 59, 59, 999_000_000)));
        assertTrue(!today.contains(at(10, 14, 0, 0)));
        assertEquals(LocalDate.of(2026, 10, 13), today.lastDay());
    }
}
