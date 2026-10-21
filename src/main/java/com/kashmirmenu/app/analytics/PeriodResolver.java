package com.kashmirmenu.app.analytics;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public final class PeriodResolver {

    /** Longest custom range accepted; keeps aggregation queries and charts bounded. */
    public static final int MAX_CUSTOM_DAYS = 366;

    private PeriodResolver() {
    }

    /**
     * @param now the current business-local time (injected so the result is testable)
     * @param from first day for CUSTOM (inclusive)
     * @param to   last day for CUSTOM (inclusive)
     */
    public static ResolvedPeriod resolve(
            PeriodPreset preset, LocalDate from, LocalDate to, LocalDateTime now) {

        LocalDate today = now.toLocalDate();
        LocalDate first;
        LocalDate last;

        switch (preset) {
            case TODAY -> {
                first = today;
                last = today;
            }
            case YESTERDAY -> {
                first = today.minusDays(1);
                last = first;
            }
            case LAST_7_DAYS -> {
                first = today.minusDays(6);
                last = today;
            }
            case LAST_30_DAYS -> {
                first = today.minusDays(29);
                last = today;
            }
            case THIS_MONTH -> {
                first = today.withDayOfMonth(1);
                last = today;
            }
            case LAST_MONTH -> {
                first = today.withDayOfMonth(1).minusMonths(1);
                last = first.withDayOfMonth(first.lengthOfMonth());
            }
            case CUSTOM -> {
                if (from == null || to == null) {
                    throw new IllegalArgumentException("A custom range needs both a start and an end date.");
                }
                if (to.isBefore(from)) {
                    throw new IllegalArgumentException("The end date is before the start date.");
                }
                if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_CUSTOM_DAYS) {
                    throw new IllegalArgumentException(
                            "Custom ranges are limited to " + MAX_CUSTOM_DAYS + " days.");
                }
                first = from;
                last = to;
            }
            default -> throw new IllegalArgumentException("Unknown period " + preset);
        }

        DateRange current = new DateRange(first.atStartOfDay(), last.plusDays(1).atStartOfDay());

        // How much of the current period has actually happened. Only differs from its full length
        // while the period still includes "now".
        LocalDateTime elapsedEnd = now.isBefore(current.endExclusive()) ? now : current.endExclusive();
        Duration elapsed = Duration.between(current.start(), elapsedEnd);
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }

        DateRange previous;
        if (preset == PeriodPreset.LAST_MONTH) {
            // A finished month is compared with the whole month before it (months differ in length).
            LocalDateTime prevStart = current.start().minusMonths(1);
            previous = new DateRange(prevStart, current.start());
        } else if (preset == PeriodPreset.THIS_MONTH) {
            // Month to date vs the same elapsed time of the previous month, never spilling into this month.
            LocalDateTime prevStart = current.start().minusMonths(1);
            LocalDateTime prevEnd = prevStart.plus(elapsed);
            if (prevEnd.isAfter(current.start())) {
                prevEnd = current.start();
            }
            previous = new DateRange(prevStart, prevEnd);
        } else {
            // Same elapsed slice of the preceding equal-length cycle.
            Duration cycle = current.length();
            LocalDateTime prevStart = current.start().minus(cycle);
            previous = new DateRange(prevStart, prevStart.plus(elapsed));
        }

        return new ResolvedPeriod(preset, current, previous);
    }
}
