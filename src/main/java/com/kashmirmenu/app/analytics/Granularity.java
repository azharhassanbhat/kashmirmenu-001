package com.kashmirmenu.app.analytics;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Time-series bucket size. Weeks start on Monday. */
public enum Granularity {
    HOURLY,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY;

    /** Upper bound on buckets in one response, so a chart can never be asked to draw an absurd axis. */
    public static final int MAX_BUCKETS = 1000;

    public LocalDateTime floor(LocalDateTime t) {
        return switch (this) {
            case HOURLY -> t.truncatedTo(ChronoUnit.HOURS);
            case DAILY -> t.toLocalDate().atStartOfDay();
            case WEEKLY -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTHLY -> t.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case YEARLY -> t.toLocalDate().withDayOfYear(1).atStartOfDay();
        };
    }

    public LocalDateTime next(LocalDateTime bucketStart) {
        return switch (this) {
            case HOURLY -> bucketStart.plusHours(1);
            case DAILY -> bucketStart.plusDays(1);
            case WEEKLY -> bucketStart.plusWeeks(1);
            case MONTHLY -> bucketStart.plusMonths(1);
            case YEARLY -> bucketStart.plusYears(1);
        };
    }

    /** Every bucket start covering the range, including empty ones, so charts get a continuous axis. */
    public List<LocalDateTime> buckets(DateRange range) {
        List<LocalDateTime> out = new ArrayList<>();
        if (range.isEmpty()) {
            return out;
        }
        LocalDateTime b = floor(range.start());
        while (b.isBefore(range.endExclusive())) {
            out.add(b);
            if (out.size() > MAX_BUCKETS) {
                throw new IllegalArgumentException(
                        "That range is too long for " + name().toLowerCase() + " buckets. Choose a coarser grouping.");
            }
            b = next(b);
        }
        return out;
    }

    /** A sensible default for the range length. */
    public static Granularity auto(DateRange range) {
        long hours = range.length().toHours();
        if (hours <= 48) {
            return HOURLY;
        }
        long days = hours / 24;
        if (days <= 62) {
            return DAILY;
        }
        if (days <= 200) {
            return WEEKLY;
        }
        return MONTHLY;
    }
}
