package com.kashmirmenu.app.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GranularityTest {

    private static LocalDateTime at(int month, int day, int hour) {
        return LocalDateTime.of(2026, month, day, hour, 0);
    }

    @Test
    void hourly_coversEveryHourOfTheDayIncludingEmptyOnes() {
        DateRange day = new DateRange(at(10, 13, 0), at(10, 14, 0));
        assertEquals(24, Granularity.HOURLY.buckets(day).size());
    }

    @Test
    void weekly_bucketsStartOnMonday() {
        // Wed 7 Oct .. Tue 13 Oct touches the weeks of Mon 5 Oct and Mon 12 Oct.
        DateRange range = new DateRange(at(10, 7, 0), at(10, 14, 0));
        var buckets = Granularity.WEEKLY.buckets(range);
        assertEquals(2, buckets.size());
        assertEquals(at(10, 5, 0), buckets.get(0));
        assertEquals(at(10, 12, 0), buckets.get(1));
        assertEquals(at(10, 12, 0), Granularity.WEEKLY.floor(at(10, 18, 23))); // Sunday belongs to that week
    }

    @Test
    void monthlyAndYearly_floorToTheFirst() {
        assertEquals(at(10, 1, 0), Granularity.MONTHLY.floor(at(10, 29, 18)));
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), Granularity.YEARLY.floor(at(10, 29, 18)));
    }

    @Test
    void anAbsurdAxisIsRejected() {
        DateRange year = new DateRange(at(1, 1, 0).withYear(2025), at(10, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Granularity.HOURLY.buckets(year));
    }

    @Test
    void auto_picksCoarserBucketsForLongerRanges() {
        assertEquals(Granularity.HOURLY, Granularity.auto(new DateRange(at(10, 13, 0), at(10, 14, 0))));
        assertEquals(Granularity.DAILY, Granularity.auto(new DateRange(at(10, 1, 0), at(10, 14, 0))));
        assertEquals(Granularity.WEEKLY, Granularity.auto(new DateRange(at(7, 1, 0), at(10, 14, 0))));
        assertEquals(Granularity.MONTHLY,
                Granularity.auto(new DateRange(at(1, 1, 0).withYear(2025), at(10, 14, 0))));
    }
}
