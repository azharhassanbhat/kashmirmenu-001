package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Whether an order is running late, worked out on the SERVER from its own clock, so a kitchen tablet
 * with a wrong time cannot show the wrong thing. Pure, so the rule is testable.
 *
 * <p>The target is the slowest dish's preparation time (a table is waiting on the last plate), or a
 * default when no dish has one set. An order is late once it has been open longer than that and the
 * kitchen has not yet finished it: PENDING (nobody has accepted it), CONFIRMED and PREPARING. Once an
 * order is READY the kitchen's part is done, so it is never "delayed" after that.
 */
public final class OrderTiming {

    public static final int DEFAULT_TARGET_MINUTES = 15;
    public static final int MAX_TARGET_MINUTES = 180;

    private OrderTiming() {
    }

    /**
     * @param elapsedMinutes whole minutes since the order was placed (never negative)
     * @param targetMinutes  how long the kitchen is expected to need
     * @param delayed        open longer than the target and not yet ready
     * @param overdueMinutes minutes beyond the target; 0 unless delayed
     */
    public record Timing(long elapsedMinutes, int targetMinutes, boolean delayed, long overdueMinutes) {
    }

    /** The slowest dish's preparation time, ignoring unset (0 or null) values; the default if none is set. */
    public static int targetMinutes(Collection<Integer> itemPrepMinutes) {
        int target = 0;
        if (itemPrepMinutes != null) {
            for (Integer m : itemPrepMinutes) {
                if (m != null && m > target) {
                    target = m;
                }
            }
        }
        if (target <= 0) {
            return DEFAULT_TARGET_MINUTES;
        }
        return Math.min(target, MAX_TARGET_MINUTES);
    }

    public static Timing of(OrderStatus status, LocalDateTime createdAt, LocalDateTime now, int targetMinutes) {
        long elapsed = (createdAt == null || now == null)
                ? 0
                : Math.max(0, Duration.between(createdAt, now).toMinutes());
        boolean open = status == OrderStatus.PENDING
                || status == OrderStatus.CONFIRMED
                || status == OrderStatus.PREPARING;
        boolean delayed = open && elapsed > targetMinutes;
        return new Timing(elapsed, targetMinutes, delayed, delayed ? elapsed - targetMinutes : 0);
    }
}
