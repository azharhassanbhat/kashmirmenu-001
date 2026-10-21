package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.BillMath.Amounts;
import com.kashmirmenu.app.analytics.BillMath.GuestAmounts;
import com.kashmirmenu.app.analytics.BillMath.OrderFacts;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.TablePayState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    /** An order of {@code total} with nothing paid yet. */
    private static OrderFacts unpaid(long guest, String name, OrderStatus status, String total) {
        return new OrderFacts(guest, name, status, d("0"), d("0"), d(total));
    }

    @Test
    void onlyConfirmedOrdersArePayable() {
        assertFalse(BillMath.isPayable(OrderStatus.PENDING, true));
        assertFalse(BillMath.isPayable(OrderStatus.CANCELLED, true));
        assertTrue(BillMath.isPayable(OrderStatus.CONFIRMED, true));
        assertTrue(BillMath.isPayable(OrderStatus.PREPARING, true));
        assertTrue(BillMath.isPayable(OrderStatus.READY, true));
        assertTrue(BillMath.isPayable(OrderStatus.SERVED, true));
    }

    @Test
    void anUnconfirmedOrderIsOwedButNotYetPayable() {
        Amounts a = BillMath.of(unpaid(1, "Rahul", OrderStatus.PENDING, "500"), true);

        assertMoney("0", a.due());
        assertMoney("500", a.awaitingConfirmation());
    }

    @Test
    void aConfirmedOrderIsDue() {
        Amounts a = BillMath.of(unpaid(1, "Rahul", OrderStatus.CONFIRMED, "500"), true);

        assertMoney("500", a.due());
        assertMoney("0", a.awaitingConfirmation());
    }

    @Test
    void aCancelledOrderIsOwedByNobody() {
        Amounts a = BillMath.of(unpaid(1, "Rahul", OrderStatus.CANCELLED, "500"), true);

        assertMoney("0", a.due());
        assertMoney("0", a.awaitingConfirmation());
        assertMoney("0", a.paid());
    }

    @Test
    void paidAndInProgressMoneyIsNotDueAgain() {
        // 1,000 order: 300 received, 200 promised but unconfirmed, 500 still free to pay.
        OrderFacts order = new OrderFacts(1, "Rahul", OrderStatus.SERVED, d("300"), d("200"), d("500"));
        Amounts a = BillMath.of(order, true);

        assertMoney("500", a.due());
        assertMoney("200", a.inProgress());
        assertMoney("300", a.paid());
        assertMoney("1000", a.due().add(a.inProgress()).add(a.paid()).add(a.awaitingConfirmation()));
    }

    @Test
    void theBriefsTableFiguresAddUp() {
        // Rahul 500 (paid), Amit 350, Priya 400: the table still owes 750.
        List<OrderFacts> orders = List.of(
                new OrderFacts(1, "Rahul", OrderStatus.SERVED, d("500"), d("0"), d("0")),
                unpaid(2, "Amit", OrderStatus.SERVED, "350"),
                unpaid(3, "Priya", OrderStatus.SERVED, "400"));

        Amounts total = BillMath.total(orders, true);
        assertMoney("750", total.due());
        assertMoney("500", total.paid());

        List<GuestAmounts> guests = BillMath.byGuest(orders, true);
        assertEquals(List.of("Rahul", "Amit", "Priya"), guests.stream().map(GuestAmounts::guestName).toList());
        assertMoney("0", guests.get(0).amounts().due());
        assertMoney("350", guests.get(1).amounts().due());
        assertMoney("400", guests.get(2).amounts().due());
    }

    @Test
    void theGuestsBreakdownAlwaysSumsToTheTableTotal() {
        List<OrderFacts> orders = List.of(
                unpaid(1, "Rahul", OrderStatus.CONFIRMED, "100.10"),
                new OrderFacts(2, "Amit", OrderStatus.READY, d("20.05"), d("30.10"), d("49.95")),
                unpaid(1, "Rahul", OrderStatus.PENDING, "75.25"),
                unpaid(3, "Priya", OrderStatus.CANCELLED, "999"),
                unpaid(2, "Amit", OrderStatus.SERVED, "10.01"));

        Amounts table = BillMath.total(orders, true);
        Amounts summed = BillMath.byGuest(orders, true).stream()
                .map(GuestAmounts::amounts)
                .reduce(Amounts.ZERO, (x, y) -> new Amounts(
                        x.due().add(y.due()), x.inProgress().add(y.inProgress()),
                        x.paid().add(y.paid()), x.awaitingConfirmation().add(y.awaitingConfirmation())));

        assertMoney(table.due().toPlainString(), summed.due());
        assertMoney(table.inProgress().toPlainString(), summed.inProgress());
        assertMoney(table.paid().toPlainString(), summed.paid());
        assertMoney(table.awaitingConfirmation().toPlainString(), summed.awaitingConfirmation());

        // 100.10 + 49.95 + 10.01 due; 30.10 in progress; 20.05 paid; 75.25 awaiting. Cancelled counts for nothing.
        assertMoney("160.06", table.due());
        assertMoney("30.10", table.inProgress());
        assertMoney("20.05", table.paid());
        assertMoney("75.25", table.awaitingConfirmation());
    }

    @Test
    void severalOrdersFromOneGuestShareOneLine() {
        List<OrderFacts> orders = List.of(
                unpaid(1, "Rahul", OrderStatus.SERVED, "100"),
                unpaid(2, "Amit", OrderStatus.SERVED, "50"),
                unpaid(1, "Rahul", OrderStatus.SERVED, "25"));

        List<GuestAmounts> guests = BillMath.byGuest(orders, true);

        assertEquals(2, guests.size());
        assertEquals("Rahul", guests.get(0).guestName());
        assertMoney("125", guests.get(0).amounts().due());
    }

    @Test
    void aGuestWhoseOrdersWereAllCancelledDoesNotAppear() {
        List<OrderFacts> orders = List.of(
                unpaid(1, "Rahul", OrderStatus.CANCELLED, "100"),
                unpaid(2, "Amit", OrderStatus.SERVED, "50"));

        assertEquals(List.of("Amit"), BillMath.byGuest(orders, true).stream().map(GuestAmounts::guestName).toList());
    }

    @Test
    void guestsWithTheSameNameAreKeptApartById() {
        List<OrderFacts> orders = List.of(
                unpaid(1, "Rahul", OrderStatus.SERVED, "100"),
                unpaid(2, "Rahul (2)", OrderStatus.SERVED, "40"));

        assertEquals(2, BillMath.byGuest(orders, true).size());
    }

    @Test
    void anEmptyTableOwesNothing() {
        assertMoney("0", BillMath.total(List.of(), true).due());
        assertTrue(BillMath.byGuest(List.of(), true).isEmpty());
    }

    @Test
    void aRestaurantThatSkipsConfirmationCanBePaidAsSoonAsOrdered() {
        assertTrue(BillMath.isPayable(OrderStatus.PENDING, false));
        assertFalse(BillMath.isPayable(OrderStatus.CANCELLED, false));

        Amounts a = BillMath.of(unpaid(1, "Rahul", OrderStatus.PENDING, "500"), false);

        assertMoney("500", a.due());
        assertMoney("0", a.awaitingConfirmation());
    }

    @Test
    void theBucketsAlwaysAddUpToTheOrdersTotal() {
        for (OrderStatus status : OrderStatus.values()) {
            for (boolean require : new boolean[]{true, false}) {
                OrderFacts order = new OrderFacts(1, "Rahul", status, d("120"), d("30"), d("50"));
                Amounts a = BillMath.of(order, require);
                BigDecimal sum = a.due().add(a.inProgress()).add(a.paid()).add(a.awaitingConfirmation());
                // 120 + 30 + 50 = 200, except a cancelled order, which is billed to nobody.
                assertMoney(status == OrderStatus.CANCELLED ? "0" : "200", sum);
            }
        }
    }

    // ------------------------------------------------------------------ how far paid

    private static Amounts amounts(String due, String inProgress, String paid, String awaiting) {
        return new Amounts(d(due), d(inProgress), d(paid), d(awaiting));
    }

    @Test
    void payStateSaysHowFarABillIsPaid() {
        assertEquals(TablePayState.NO_ORDERS, BillMath.payState(Amounts.ZERO));
        assertEquals(TablePayState.UNPAID, BillMath.payState(amounts("500", "0", "0", "0")));
        assertEquals(TablePayState.PAID, BillMath.payState(amounts("0", "0", "500", "0")));
        assertEquals(TablePayState.PARTIALLY_PAID, BillMath.payState(amounts("250", "0", "500", "0")));
    }

    @Test
    void moneyWaitingForStaffOrTheKitchenIsNotPaid() {
        // A payment staff have not confirmed is not money received.
        assertEquals(TablePayState.UNPAID, BillMath.payState(amounts("0", "500", "0", "0")));
        // An order the kitchen has not confirmed is still owed.
        assertEquals(TablePayState.UNPAID, BillMath.payState(amounts("0", "0", "0", "500")));
        // Part received and part still waiting is part paid, never paid.
        assertEquals(TablePayState.PARTIALLY_PAID, BillMath.payState(amounts("0", "250", "250", "0")));
        assertEquals(TablePayState.PARTIALLY_PAID, BillMath.payState(amounts("0", "0", "250", "250")));
    }

    @Test
    void aCancelledOrderIsNeitherPaidNorUnpaid() {
        assertEquals(TablePayState.NO_ORDERS,
                BillMath.payState(BillMath.of(unpaid(1, "Rahul", OrderStatus.CANCELLED, "500"), true)));
    }

    @Test
    void theBriefsTable5ReadsPartiallyPaid() {
        // Rahul 500 paid, Amit 350 and Priya 400 unpaid: the table is partially paid and owes 750.
        List<OrderFacts> orders = List.of(
                new OrderFacts(1, "Rahul", OrderStatus.SERVED, d("500"), d("0"), d("0")),
                unpaid(2, "Amit", OrderStatus.SERVED, "350"),
                unpaid(3, "Priya", OrderStatus.SERVED, "400"));

        Amounts table = BillMath.total(orders, true);

        assertEquals(TablePayState.PARTIALLY_PAID, BillMath.payState(table));
        assertMoney("750", table.due());

        List<GuestAmounts> guests = BillMath.byGuest(orders, true);
        assertEquals(TablePayState.PAID, BillMath.payState(guests.get(0).amounts()));
        assertEquals(TablePayState.UNPAID, BillMath.payState(guests.get(1).amounts()));
        assertEquals(TablePayState.UNPAID, BillMath.payState(guests.get(2).amounts()));
    }
}
