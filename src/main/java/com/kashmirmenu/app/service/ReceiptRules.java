package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.exception.BadRequestException;

import java.math.BigDecimal;

/**
 * The wording and small rules of a receipt. Pure, so they are testable. The amounts themselves are never
 * recalculated here: a receipt prints what the order recorded when it was placed (the prices, rates and
 * totals the guest was shown), so it can never disagree with the bill.
 */
public final class ReceiptRules {

    private ReceiptRules() {
    }

    /** A cancelled order was never a sale, so it has no receipt. */
    public static void requireReceiptable(OrderStatus status) {
        if (status == OrderStatus.CANCELLED) {
            throw new BadRequestException("A cancelled order has no receipt.");
        }
    }

    /**
     * The big word at the top of the payment section.
     *
     * @param total       the bill
     * @param settled     money received and not refunded
     * @param outstanding what is still owed
     * @param refunded    money given back
     */
    public static String heading(BigDecimal total, BigDecimal settled, BigDecimal outstanding, BigDecimal refunded) {
        if (total == null || total.signum() <= 0) {
            return "NO PAYMENT DUE";
        }
        if (settled == null || settled.signum() <= 0) {
            return refunded != null && refunded.signum() > 0 ? "REFUNDED" : "UNPAID";
        }
        return outstanding == null || outstanding.signum() <= 0 ? "PAID" : "PART PAID";
    }

    /** How a payment is described: "Cash", "UPI (Google Pay)", "Card". An unspecific provider adds nothing. */
    public static String methodLabel(PaymentMethod method, PaymentProvider provider) {
        String base = method == null ? "Payment" : method.label();
        if (provider == null || provider == PaymentProvider.OTHER_UPI || provider == PaymentProvider.OTHER_WALLET) {
            return base;
        }
        return base + " (" + provider.label() + ")";
    }

    /**
     * Money given back: what was received minus what is still settled. Never negative, so a rounding
     * difference or an odd record cannot print a negative refund.
     *
     * @param captured total of the payments that were actually received (before any refund)
     * @param settled  what remains after refunds
     */
    public static BigDecimal refunded(BigDecimal captured, BigDecimal settled) {
        if (captured == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal back = captured.subtract(settled == null ? BigDecimal.ZERO : settled);
        return back.signum() < 0 ? BigDecimal.ZERO : back;
    }
}
