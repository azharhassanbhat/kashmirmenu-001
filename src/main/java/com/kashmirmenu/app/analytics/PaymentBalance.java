package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an order has paid and still owes, computed from its individual payment records. Orders can be
 * paid in parts (split bills, partial payments) and payments can fail and be retried or be refunded,
 * so the balance is always derived from the records and never stored.
 */
public final class PaymentBalance {

    private PaymentBalance() {
    }

    public record Line(PaymentStatus status, BigDecimal amount, BigDecimal refunded) {
    }

    /**
     * @param settled     money received and kept: captured amounts minus refunds
     * @param pending     amount promised but not yet confirmed (does not count as paid)
     * @param outstanding what is still due: total minus settled minus pending, never negative
     * @param status      one summary status for the order, or null when nothing meaningful can be said
     */
    public record Result(BigDecimal settled, BigDecimal pending, BigDecimal outstanding, PaymentStatus status) {
    }

    public static Result of(BigDecimal orderTotal, List<Line> lines) {
        BigDecimal settled = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        boolean anyCaptured = false;
        boolean anyFailed = false;

        for (Line l : lines) {
            BigDecimal refunded = l.refunded() == null ? BigDecimal.ZERO : l.refunded();
            if (l.status().isCaptured()) {
                anyCaptured = true;
                settled = settled.add(l.amount().subtract(refunded));
            } else if (l.status() == PaymentStatus.PENDING) {
                pending = pending.add(l.amount());
            } else {
                anyFailed = true; // FAILED or CANCELLED
            }
        }

        BigDecimal outstanding = orderTotal.subtract(settled).subtract(pending);
        if (outstanding.signum() < 0) {
            outstanding = BigDecimal.ZERO;
        }

        PaymentStatus status;
        if (anyCaptured && outstanding.signum() == 0 && pending.signum() == 0) {
            status = PaymentStatus.SUCCESS;
        } else if (pending.signum() > 0) {
            status = PaymentStatus.PENDING;
        } else if (!anyCaptured && anyFailed) {
            status = PaymentStatus.FAILED;
        } else {
            status = null; // nothing paid yet, or only part paid with nothing in flight
        }
        return new Result(MoneyMath.money(settled), MoneyMath.money(pending), MoneyMath.money(outstanding), status);
    }
}
