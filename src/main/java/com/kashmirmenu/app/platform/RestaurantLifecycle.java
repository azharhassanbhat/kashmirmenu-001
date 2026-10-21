package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.exception.ConflictException;

/**
 * The allowed moves between restaurant statuses, in one place and free of I/O so they can be tested
 * exhaustively.
 *
 * <pre>
 *   PENDING   -> APPROVED | REJECTED
 *   REJECTED  -> APPROVED            (the platform owner may reconsider)
 *   APPROVED  -> SUSPENDED
 *   SUSPENDED -> APPROVED            (reactivation)
 * </pre>
 *
 * Anything else is a conflict (409): in particular a decision already taken cannot be taken again,
 * which is also what makes two simultaneous clicks safe.
 */
public final class RestaurantLifecycle {

    private RestaurantLifecycle() {
    }

    public static boolean canApprove(RestaurantStatus current) {
        return current == RestaurantStatus.PENDING || current == RestaurantStatus.REJECTED;
    }

    public static boolean canReject(RestaurantStatus current) {
        return current == RestaurantStatus.PENDING;
    }

    public static boolean canSuspend(RestaurantStatus current) {
        return current == RestaurantStatus.APPROVED;
    }

    public static boolean canReactivate(RestaurantStatus current) {
        return current == RestaurantStatus.SUSPENDED;
    }

    public static void requireApprove(RestaurantStatus current) {
        require(canApprove(current), "approved", current);
    }

    public static void requireReject(RestaurantStatus current) {
        require(canReject(current), "rejected", current);
    }

    public static void requireSuspend(RestaurantStatus current) {
        require(canSuspend(current), "suspended", current);
    }

    public static void requireReactivate(RestaurantStatus current) {
        require(canReactivate(current), "reactivated", current);
    }

    private static void require(boolean allowed, String verb, RestaurantStatus current) {
        if (!allowed) {
            throw new ConflictException(
                    "This restaurant is " + current.name().toLowerCase() + " and cannot be " + verb + ".");
        }
    }
}
