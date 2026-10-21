package com.kashmirmenu.app.analytics;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A half-open interval [start, endExclusive) of business-local date-times. Half-open ranges make the
 * day boundaries unambiguous: a payment at 23:59:59.999 belongs to its day and one at 00:00:00 to the next.
 */
public record DateRange(LocalDateTime start, LocalDateTime endExclusive) {

    public DateRange {
        if (start == null || endExclusive == null) {
            throw new IllegalArgumentException("A date range needs a start and an end.");
        }
        if (endExclusive.isBefore(start)) {
            throw new IllegalArgumentException("The end of the range is before its start.");
        }
    }

    public boolean isEmpty() {
        return !endExclusive.isAfter(start);
    }

    public Duration length() {
        return Duration.between(start, endExclusive);
    }

    public boolean contains(LocalDateTime t) {
        return !t.isBefore(start) && t.isBefore(endExclusive);
    }

    /** First calendar day covered (inclusive). */
    public LocalDate firstDay() {
        return start.toLocalDate();
    }

    /** Last calendar day covered (inclusive); equals the first day for an empty range. */
    public LocalDate lastDay() {
        return isEmpty() ? start.toLocalDate() : endExclusive.minusNanos(1).toLocalDate();
    }
}
