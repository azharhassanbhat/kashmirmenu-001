package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.SettlementRules.Candidate;
import com.kashmirmenu.app.analytics.SettlementRules.Plan;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.enums.SettlementKind;
import com.kashmirmenu.app.enums.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static Candidate order(long id, long guest, OrderStatus status, String outstanding) {
        return new Candidate(id, guest, status, d(outstanding));
    }

    /** Table 5: Rahul (guest 1) 500, Amit (2) 350, Priya (3) 400; all served. */
    private static List<Candidate> table5() {
        return List.of(
                order(10, 1, OrderStatus.SERVED, "500"),
                order(11, 2, OrderStatus.SERVED, "350"),
                order(12, 3, OrderStatus.SERVED, "400"));
    }

    // ------------------------------------------------------------------ what a settlement covers

    @Test
    void payingSeparatelyCoversOnlyThePayersOwnOrders() {
        Plan plan = SettlementRules.plan(SettlementKind.SEPARATE, 2, table5(), true);

        assertEquals(1, plan.lines().size());
        assertEquals(11L, plan.lines().get(0).orderId());
        assertMoney("350", plan.total());
    }

    @Test
    void payingTogetherCoversEveryonesOutstandingOrders() {
        Plan plan = SettlementRules.plan(SettlementKind.COMBINED, 2, table5(), true);

        assertEquals(List.of(10L, 11L, 12L), plan.lines().stream().map(SettlementRules.Line::orderId).toList());
        assertMoney("1250", plan.total());
    }

    @Test
    void anAlreadyPaidOrderIsNeverChargedAgain() {
        // Rahul's 500 is settled, so nothing is free to pay on his order.
        List<Candidate> orders = List.of(
                order(10, 1, OrderStatus.SERVED, "0"),
                order(11, 2, OrderStatus.SERVED, "350"),
                order(12, 3, OrderStatus.SERVED, "400"));

        Plan plan = SettlementRules.plan(SettlementKind.COMBINED, 3, orders, true);

        assertMoney("750", plan.total());
        assertFalse(plan.lines().stream().anyMatch(l -> l.orderId() == 10));
    }

    @Test
    void anOrderWithAPaymentInProgressIsLeftOut() {
        // A payment in progress already takes the order's whole outstanding amount, so what is free is 0.
        List<Candidate> orders = List.of(
                order(10, 1, OrderStatus.SERVED, "0"),
                order(11, 2, OrderStatus.SERVED, "350"));

        assertMoney("350", SettlementRules.plan(SettlementKind.COMBINED, 2, orders, true).total());
        assertTrue(SettlementRules.plan(SettlementKind.SEPARATE, 1, orders, true).isEmpty());
    }

    @Test
    void aCancelledOrderIsNeverCovered() {
        List<Candidate> orders = List.of(
                order(10, 1, OrderStatus.CANCELLED, "500"),
                order(11, 1, OrderStatus.SERVED, "100"));

        Plan plan = SettlementRules.plan(SettlementKind.SEPARATE, 1, orders, true);

        assertEquals(1, plan.lines().size());
        assertMoney("100", plan.total());
    }

    @Test
    void anUnconfirmedOrderWaits_andIsCountedSoTheCallerCanSayWhy() {
        List<Candidate> orders = List.of(
                order(10, 1, OrderStatus.PENDING, "500"),
                order(11, 1, OrderStatus.CONFIRMED, "100"));

        Plan plan = SettlementRules.plan(SettlementKind.SEPARATE, 1, orders, true);

        assertMoney("100", plan.total());
        assertEquals(1, plan.awaitingConfirmation());

        Plan onlyUnconfirmed = SettlementRules.plan(SettlementKind.SEPARATE, 1,
                List.of(order(10, 1, OrderStatus.PENDING, "500")), true);
        assertTrue(onlyUnconfirmed.isEmpty());
        assertEquals(1, onlyUnconfirmed.awaitingConfirmation());
    }

    @Test
    void whenConfirmationIsNotRequiredAPendingOrderIsPayable() {
        Plan plan = SettlementRules.plan(SettlementKind.SEPARATE, 1,
                List.of(order(10, 1, OrderStatus.PENDING, "500")), false);

        assertMoney("500", plan.total());
        assertEquals(0, plan.awaitingConfirmation());
    }

    @Test
    void severalOrdersFromOneGuestAreAllCovered_inOrderIdOrder() {
        List<Candidate> orders = List.of(
                order(30, 1, OrderStatus.SERVED, "10.10"),
                order(10, 1, OrderStatus.SERVED, "20.20"),
                order(20, 2, OrderStatus.SERVED, "5"));

        Plan plan = SettlementRules.plan(SettlementKind.SEPARATE, 1, orders, true);

        assertEquals(List.of(10L, 30L), plan.lines().stream().map(SettlementRules.Line::orderId).toList());
        assertMoney("30.30", plan.total());
    }

    @Test
    void theTotalIsExactlyTheSumOfTheLines() {
        List<Candidate> orders = List.of(
                order(1, 1, OrderStatus.SERVED, "0.10"),
                order(2, 2, OrderStatus.SERVED, "0.20"),
                order(3, 3, OrderStatus.SERVED, "0.30"));

        Plan plan = SettlementRules.plan(SettlementKind.COMBINED, 1, orders, true);

        BigDecimal sum = plan.lines().stream().map(SettlementRules.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(plan.total()));
        assertMoney("0.60", plan.total());
    }

    @Test
    void nothingToPayGivesAnEmptyPlan() {
        assertTrue(SettlementRules.plan(SettlementKind.COMBINED, 1, List.of(), true).isEmpty());
    }

    // ------------------------------------------------------------------ a settlement's status

    @Test
    void aSettlementIsPendingWhileAnyPaymentIs() {
        assertEquals(SettlementStatus.PENDING, SettlementRules.statusOf(
                List.of(PaymentStatus.PENDING, PaymentStatus.PENDING)));
        assertEquals(SettlementStatus.PENDING, SettlementRules.statusOf(
                List.of(PaymentStatus.SUCCESS, PaymentStatus.PENDING)));
        assertEquals(SettlementStatus.PENDING, SettlementRules.statusOf(
                List.of(PaymentStatus.CANCELLED, PaymentStatus.PENDING)));
    }

    @Test
    void aSettlementSucceedsOnlyWhenEveryPaymentWasReceived() {
        assertEquals(SettlementStatus.SUCCESS, SettlementRules.statusOf(
                List.of(PaymentStatus.SUCCESS, PaymentStatus.SUCCESS)));
        // A later refund does not turn a received payment back into a failure.
        assertEquals(SettlementStatus.SUCCESS, SettlementRules.statusOf(
                List.of(PaymentStatus.SUCCESS, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED)));
    }

    @Test
    void aSettlementThatReceivedNothingFailsOrIsCancelled() {
        assertEquals(SettlementStatus.FAILED, SettlementRules.statusOf(
                List.of(PaymentStatus.FAILED, PaymentStatus.FAILED)));
        assertEquals(SettlementStatus.CANCELLED, SettlementRules.statusOf(
                List.of(PaymentStatus.CANCELLED, PaymentStatus.CANCELLED)));
        assertEquals(SettlementStatus.FAILED, SettlementRules.statusOf(
                List.of(PaymentStatus.FAILED, PaymentStatus.CANCELLED)));
    }

    @Test
    void aSettlementThatReceivedSomeButNotAllIsPartial() {
        assertEquals(SettlementStatus.PARTIAL, SettlementRules.statusOf(
                List.of(PaymentStatus.SUCCESS, PaymentStatus.CANCELLED)));
        assertEquals(SettlementStatus.PARTIAL, SettlementRules.statusOf(
                List.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED)));
    }

    @Test
    void aSettlementWithNoPaymentsIsCancelled() {
        assertEquals(SettlementStatus.CANCELLED, SettlementRules.statusOf(List.of()));
    }
}
