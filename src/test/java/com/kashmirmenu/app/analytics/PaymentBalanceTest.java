package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.PaymentBalance.Line;
import com.kashmirmenu.app.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaymentBalanceTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Line line(PaymentStatus s, String amount) {
        return new Line(s, d(amount), BigDecimal.ZERO);
    }

    @Test
    void anUnpaidOrderOwesEverythingAndHasNoStatus() {
        PaymentBalance.Result r = PaymentBalance.of(d("1200.00"), List.of());
        assertEquals(d("1200.00"), r.outstanding());
        assertNull(r.status());
    }

    @Test
    void aSplitBillIsPaidWhenTheComponentsAddUp() {
        // Example A from the brief: 1,200 = 500 cash + 700 Google Pay.
        PaymentBalance.Result r = PaymentBalance.of(d("1200.00"), List.of(
                line(PaymentStatus.SUCCESS, "500.00"), line(PaymentStatus.SUCCESS, "700.00")));
        assertEquals(d("1200.00"), r.settled());
        assertEquals(d("0.00"), r.outstanding());
        assertEquals(PaymentStatus.SUCCESS, r.status());
    }

    @Test
    void aPartialPaymentLeavesABalanceAndNoStatusSoTheRestCanBePaid() {
        PaymentBalance.Result r = PaymentBalance.of(d("2000.00"), List.of(
                line(PaymentStatus.SUCCESS, "1000.00"), line(PaymentStatus.PARTIALLY_REFUNDED, "600.00")));
        assertEquals(d("1600.00"), r.settled());
        assertEquals(d("400.00"), r.outstanding());
        assertNull(r.status());
    }

    @Test
    void pendingMoneyIsNotPaidButIsSpokenFor() {
        PaymentBalance.Result r = PaymentBalance.of(d("1000.00"), List.of(
                line(PaymentStatus.SUCCESS, "400.00"), line(PaymentStatus.PENDING, "600.00")));
        assertEquals(d("400.00"), r.settled());
        assertEquals(d("600.00"), r.pending());
        assertEquals(d("0.00"), r.outstanding());
        assertEquals(PaymentStatus.PENDING, r.status());
    }

    @Test
    void aFailedPaymentCanBeRetried() {
        PaymentBalance.Result failed = PaymentBalance.of(d("500.00"), List.of(line(PaymentStatus.FAILED, "500.00")));
        assertEquals(d("500.00"), failed.outstanding()); // the failed attempt does not reduce what is owed
        assertEquals(PaymentStatus.FAILED, failed.status());

        PaymentBalance.Result retried = PaymentBalance.of(d("500.00"), List.of(
                line(PaymentStatus.FAILED, "500.00"), line(PaymentStatus.SUCCESS, "500.00")));
        assertEquals(PaymentStatus.SUCCESS, retried.status());
    }

    @Test
    void aFullyRefundedPaymentReopensTheBalance() {
        PaymentBalance.Result r = PaymentBalance.of(d("500.00"), List.of(
                new Line(PaymentStatus.REFUNDED, d("500.00"), d("500.00"))));
        assertEquals(d("0.00"), r.settled());
        assertEquals(d("500.00"), r.outstanding());
        assertNull(r.status());
    }

    @Test
    void cancelledPaymentsDoNotCount() {
        PaymentBalance.Result r = PaymentBalance.of(d("300.00"), List.of(line(PaymentStatus.CANCELLED, "300.00")));
        assertEquals(d("300.00"), r.outstanding());
        assertEquals(d("0.00"), r.settled());
    }
}
