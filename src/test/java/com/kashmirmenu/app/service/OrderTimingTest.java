package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.service.OrderTiming.Timing;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Whether an order is running late: slowest dish sets the target, only unfinished orders can be late. */
class OrderTimingTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 15, 13, 0, 0);

    private static Timing at(OrderStatus status, int minutesLater, int target) {
        return OrderTiming.of(status, T0, T0.plusMinutes(minutesLater), target);
    }

    @Test
    void theTargetIsTheSlowestDishNotTheAverage() {
        assertEquals(25, OrderTiming.targetMinutes(List.of(5, 25, 10)));
    }

    @Test
    void unsetPrepTimesAreIgnoredAndADefaultIsUsedWhenNoneIsSet() {
        assertEquals(12, OrderTiming.targetMinutes(Arrays.asList(0, null, 12)));
        assertEquals(OrderTiming.DEFAULT_TARGET_MINUTES, OrderTiming.targetMinutes(List.of(0, 0)));
        assertEquals(OrderTiming.DEFAULT_TARGET_MINUTES, OrderTiming.targetMinutes(List.of()));
        assertEquals(OrderTiming.DEFAULT_TARGET_MINUTES, OrderTiming.targetMinutes(null));
        assertEquals(OrderTiming.DEFAULT_TARGET_MINUTES, OrderTiming.targetMinutes(Arrays.asList((Integer) null)));
    }

    @Test
    void anAbsurdPrepTimeIsCapped() {
        assertEquals(OrderTiming.MAX_TARGET_MINUTES, OrderTiming.targetMinutes(List.of(9999)));
    }

    @Test
    void anOrderWithinItsTargetIsNotDelayed() {
        Timing t = at(OrderStatus.PREPARING, 10, 15);
        assertEquals(10, t.elapsedMinutes());
        assertEquals(15, t.targetMinutes());
        assertFalse(t.delayed());
        assertEquals(0, t.overdueMinutes());
    }

    @Test
    void exactlyOnTheTargetIsNotYetDelayed() {
        assertFalse(at(OrderStatus.PREPARING, 15, 15).delayed());
        assertTrue(at(OrderStatus.PREPARING, 16, 15).delayed());
    }

    @Test
    void aLateOrderSaysHowLate() {
        Timing t = at(OrderStatus.CONFIRMED, 22, 15);
        assertTrue(t.delayed());
        assertEquals(7, t.overdueMinutes());
    }

    @Test
    void anOrderNobodyHasAcceptedCanBeLateToo() {
        assertTrue(at(OrderStatus.PENDING, 30, 15).delayed());
    }

    @Test
    void onceTheKitchenIsDoneNothingIsDelayed() {
        for (OrderStatus s : new OrderStatus[]{OrderStatus.READY, OrderStatus.SERVED, OrderStatus.CANCELLED}) {
            Timing t = at(s, 600, 15);
            assertFalse(t.delayed(), s.name());
            assertEquals(0, t.overdueMinutes(), s.name());
            assertEquals(600, t.elapsedMinutes());
        }
    }

    @Test
    void aClockThatRunsBackwardsNeverGivesNegativeTime() {
        Timing t = OrderTiming.of(OrderStatus.PREPARING, T0, T0.minusMinutes(5), 15);
        assertEquals(0, t.elapsedMinutes());
        assertFalse(t.delayed());
    }

    @Test
    void missingTimesAreHandledNotFatal() {
        assertEquals(0, OrderTiming.of(OrderStatus.PENDING, null, T0, 15).elapsedMinutes());
        assertEquals(0, OrderTiming.of(OrderStatus.PENDING, T0, null, 15).elapsedMinutes());
    }

    @Test
    void partialMinutesAreRoundedDown() {
        assertEquals(15, OrderTiming.of(OrderStatus.PREPARING, T0, T0.plusSeconds(15 * 60 + 59), 15).elapsedMinutes());
        assertFalse(OrderTiming.of(OrderStatus.PREPARING, T0, T0.plusSeconds(15 * 60 + 59), 15).delayed());
    }
}
