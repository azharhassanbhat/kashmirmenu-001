package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;

/**
 * The single source of truth for which order status changes are allowed.
 *
 * <p>This table already existed, privately, in {@code KitchenServiceImpl} - but
 * {@code PUT /api/v1/orders/{id}/status} wrote to the same field without consulting it, so
 * SERVED to PENDING, or CANCELLED to PREPARING, were both accepted. Two write paths to one field,
 * one of them validated, is the whole of BUG-003.
 *
 * <p>The rules are unchanged from the kitchen's version, and they match what the admin Orders page
 * already offers (it shows Cancel only for PENDING and CONFIRMED), so nothing a user could
 * legitimately do before becomes impossible now.
 */
public final class OrderStatusPolicy {

    private OrderStatusPolicy() {
    }

    /** Whether {@code next} may follow {@code current}. */
    public static boolean isAllowed(OrderStatus current, OrderStatus next) {

        if (current == next) {
            // Re-applying the status a caller already sees is a no-op, not an error: a retried
            // request after a dropped response must not look like a failure.
            return true;
        }

        return switch (current) {

            case PENDING ->
                    next == OrderStatus.CONFIRMED
                            || next == OrderStatus.CANCELLED;

            case CONFIRMED ->
                    next == OrderStatus.PREPARING
                            || next == OrderStatus.CANCELLED;

            // Once the kitchen has started, cancelling is no longer a status change - the food
            // exists. That is a refund conversation, not a state transition.
            case PREPARING ->
                    next == OrderStatus.READY;

            case READY ->
                    next == OrderStatus.SERVED;

            // Terminal.
            case SERVED, CANCELLED ->
                    false;
        };
    }

    /** The message shown when a transition is refused. */
    public static String refusalMessage(OrderStatus current, OrderStatus next) {
        return "Cannot change order status from " + current + " to " + next;
    }
}
