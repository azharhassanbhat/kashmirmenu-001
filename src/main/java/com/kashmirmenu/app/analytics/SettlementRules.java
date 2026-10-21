package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.enums.SettlementKind;
import com.kashmirmenu.app.enums.SettlementStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The rules for what a settlement covers and what state it is in. Pure arithmetic with no database,
 * so they can be tested on their own.
 */
public final class SettlementRules {

    private SettlementRules() {
    }

    /** An order of the visit, as read under its row lock: its status and what is free to pay. */
    public record Candidate(long orderId, long guestId, OrderStatus status, BigDecimal outstanding) {
    }

    /** One order a settlement covers, and the amount taken for it. */
    public record Line(long orderId, long guestId, BigDecimal amount) {
    }

    /**
     * @param lines                orders covered, in order-id order
     * @param total                the sum of the lines
     * @param awaitingConfirmation orders that still owe money but are not payable yet because the
     *                             kitchen has not confirmed them (used to explain an empty result)
     */
    public record Plan(List<Line> lines, BigDecimal total, int awaitingConfirmation) {
        public boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    /**
     * What a settlement of this kind covers right now.
     *
     * <p>SEPARATE covers the payer's own payable orders; COMBINED covers everyone's. Either way an
     * order is covered only if it is payable and has something free to pay, so an order that is
     * already paid, or already has a payment in progress, is never taken a second time. Orders placed
     * after this is computed are not part of it: they stay a separate outstanding balance.
     */
    public static Plan plan(
            SettlementKind kind,
            long payerGuestId,
            Collection<Candidate> candidates,
            boolean requireConfirmedOrders) {

        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong(Candidate::orderId));

        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int awaiting = 0;

        for (Candidate c : ordered) {
            if (kind == SettlementKind.SEPARATE && c.guestId() != payerGuestId) {
                continue;
            }
            if (!BillMath.isBilled(c.status()) || c.outstanding().signum() <= 0) {
                continue;
            }
            if (!BillMath.isPayable(c.status(), requireConfirmedOrders)) {
                awaiting++;
                continue;
            }
            BigDecimal amount = MoneyMath.money(c.outstanding());
            lines.add(new Line(c.orderId(), c.guestId(), amount));
            total = total.add(amount);
        }
        return new Plan(lines, MoneyMath.money(total), awaiting);
    }

    /** A settlement's state from the states of its payments. */
    public static SettlementStatus statusOf(Collection<PaymentStatus> payments) {
        if (payments.isEmpty()) {
            return SettlementStatus.CANCELLED;
        }
        boolean anyPending = false;
        int captured = 0;
        int failed = 0;
        int cancelled = 0;

        for (PaymentStatus s : payments) {
            if (s == PaymentStatus.PENDING) {
                anyPending = true;
            } else if (s.isCaptured()) {
                captured++;
            } else if (s == PaymentStatus.FAILED) {
                failed++;
            } else {
                cancelled++;
            }
        }

        if (anyPending) {
            return SettlementStatus.PENDING;
        }
        if (captured == payments.size()) {
            return SettlementStatus.SUCCESS;
        }
        if (captured > 0) {
            return SettlementStatus.PARTIAL;
        }
        // Nothing was received. All cancelled is a cancellation; any failure makes it a failure.
        return failed > 0 ? SettlementStatus.FAILED : SettlementStatus.CANCELLED;
    }
}
