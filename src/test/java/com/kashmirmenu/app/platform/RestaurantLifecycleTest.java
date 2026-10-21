package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.exception.ConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestaurantLifecycleTest {

    @Test
    void pendingCanBeApprovedOrRejectedButNotSuspendedOrReactivated() {
        RestaurantStatus s = RestaurantStatus.PENDING;
        assertTrue(RestaurantLifecycle.canApprove(s));
        assertTrue(RestaurantLifecycle.canReject(s));
        assertFalse(RestaurantLifecycle.canSuspend(s));
        assertFalse(RestaurantLifecycle.canReactivate(s));
    }

    @Test
    void rejectedCanOnlyBeReconsideredByApproving() {
        RestaurantStatus s = RestaurantStatus.REJECTED;
        assertTrue(RestaurantLifecycle.canApprove(s));
        assertFalse(RestaurantLifecycle.canReject(s));
        assertFalse(RestaurantLifecycle.canSuspend(s));
        assertFalse(RestaurantLifecycle.canReactivate(s));
    }

    @Test
    void approvedCanOnlyBeSuspended() {
        RestaurantStatus s = RestaurantStatus.APPROVED;
        assertFalse(RestaurantLifecycle.canApprove(s));
        assertFalse(RestaurantLifecycle.canReject(s));
        assertTrue(RestaurantLifecycle.canSuspend(s));
        assertFalse(RestaurantLifecycle.canReactivate(s));
    }

    @Test
    void suspendedCanOnlyBeReactivated() {
        RestaurantStatus s = RestaurantStatus.SUSPENDED;
        assertFalse(RestaurantLifecycle.canApprove(s));
        assertFalse(RestaurantLifecycle.canReject(s));
        assertFalse(RestaurantLifecycle.canSuspend(s));
        assertTrue(RestaurantLifecycle.canReactivate(s));
    }

    @Test
    void anInvalidMoveIsAConflictThatNamesTheCurrentState() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> RestaurantLifecycle.requireApprove(RestaurantStatus.APPROVED));
        assertTrue(ex.getMessage().contains("approved"));

        assertThrows(ConflictException.class, () -> RestaurantLifecycle.requireReject(RestaurantStatus.SUSPENDED));
        assertThrows(ConflictException.class, () -> RestaurantLifecycle.requireSuspend(RestaurantStatus.PENDING));
        assertThrows(ConflictException.class, () -> RestaurantLifecycle.requireReactivate(RestaurantStatus.APPROVED));
        assertDoesNotThrow(() -> RestaurantLifecycle.requireApprove(RestaurantStatus.PENDING));
    }
}
