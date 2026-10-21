package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** The wording and small rules of a receipt. */
class ReceiptRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // ------------------------------------------------------------------ who gets a receipt

    @Test
    void everyOrderThatWasASaleHasAReceipt() {
        for (OrderStatus s : new OrderStatus[]{OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PREPARING,
                OrderStatus.READY, OrderStatus.SERVED}) {
            assertDoesNotThrow(() -> ReceiptRules.requireReceiptable(s), s.name());
        }
    }

    @Test
    void aCancelledOrderHasNone() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> ReceiptRules.requireReceiptable(OrderStatus.CANCELLED));
        assertTrue(e.getMessage().contains("cancelled"));
    }

    // ------------------------------------------------------------------ the heading

    @Test
    void nothingPaidIsUnpaid() {
        assertEquals("UNPAID", ReceiptRules.heading(d("462"), d("0"), d("462"), d("0")));
    }

    @Test
    void paidInFullIsPaid() {
        assertEquals("PAID", ReceiptRules.heading(d("462"), d("462"), d("0"), d("0")));
    }

    @Test
    void somethingPaidButNotAllIsPartPaid() {
        assertEquals("PART PAID", ReceiptRules.heading(d("462"), d("200"), d("262"), d("0")));
    }

    @Test
    void anOverpaymentStillReadsPaidNotNegative() {
        assertEquals("PAID", ReceiptRules.heading(d("462"), d("500"), d("-38"), d("0")));
    }

    @Test
    void aFreeBillNeedsNoPayment() {
        assertEquals("NO PAYMENT DUE", ReceiptRules.heading(d("0"), d("0"), d("0"), d("0")));
        assertEquals("NO PAYMENT DUE", ReceiptRules.heading(null, null, null, null));
    }

    @Test
    void aFullyRefundedBillSaysRefundedNotUnpaid() {
        assertEquals("REFUNDED", ReceiptRules.heading(d("462"), d("0"), d("462"), d("462")));
    }

    @Test
    void aPartlyRefundedBillIsJudgedOnWhatIsLeftSettled() {
        assertEquals("PART PAID", ReceiptRules.heading(d("462"), d("300"), d("162"), d("162")));
    }

    // ------------------------------------------------------------------ how a payment reads

    @Test
    void cashAndCardAreJustNamed() {
        assertEquals("Cash", ReceiptRules.methodLabel(PaymentMethod.CASH, null));
        assertEquals("Card", ReceiptRules.methodLabel(PaymentMethod.CARD, null));
    }

    @Test
    void aKnownUpiAppIsNamed() {
        assertEquals("UPI (Google Pay)", ReceiptRules.methodLabel(PaymentMethod.UPI, PaymentProvider.GOOGLE_PAY));
        assertEquals("UPI (PhonePe)", ReceiptRules.methodLabel(PaymentMethod.UPI, PaymentProvider.PHONEPE));
    }

    @Test
    void anUnspecificProviderAddsNothing() {
        assertEquals("UPI", ReceiptRules.methodLabel(PaymentMethod.UPI, PaymentProvider.OTHER_UPI));
        assertEquals("Wallet", ReceiptRules.methodLabel(PaymentMethod.WALLET, PaymentProvider.OTHER_WALLET));
        assertEquals("UPI", ReceiptRules.methodLabel(PaymentMethod.UPI, null));
    }

    @Test
    void aMissingMethodIsHandled() {
        assertEquals("Payment", ReceiptRules.methodLabel(null, null));
    }

    // ------------------------------------------------------------------ refunds

    @Test
    void refundedIsWhatWasReceivedMinusWhatRemainsSettled() {
        assertEquals(0, d("150").compareTo(ReceiptRules.refunded(d("450"), d("300"))));
        assertEquals(0, d("462").compareTo(ReceiptRules.refunded(d("462"), d("0"))));
        assertEquals(0, d("0").compareTo(ReceiptRules.refunded(d("462"), d("462"))));
    }

    @Test
    void refundedIsNeverNegativeWhateverTheRecordsSay() {
        assertEquals(0, BigDecimal.ZERO.compareTo(ReceiptRules.refunded(d("100"), d("130"))));
    }

    @Test
    void missingFiguresMeanNothingRefunded() {
        assertEquals(0, BigDecimal.ZERO.compareTo(ReceiptRules.refunded(null, d("5"))));
        assertEquals(0, d("40").compareTo(ReceiptRules.refunded(d("40"), null)));
    }
}
