package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.TablePayState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What guests at a table owe, worked out from their orders and each order's payment balance.
 * Pure arithmetic with no database, so the rules are testable on their own.
 *
 * <p>Every order falls into one of four buckets, and the buckets add up to the order's total:
 * <ul>
 *   <li><b>paid</b>: money received and kept (settled);</li>
 *   <li><b>in progress</b>: a payment someone has started that staff have not confirmed yet;</li>
 *   <li><b>due</b>: still to be paid, on an order the kitchen has confirmed;</li>
 *   <li><b>awaiting confirmation</b>: still to be paid, on an order the kitchen has not confirmed yet.</li>
 * </ul>
 *
 * <p>Only <b>due</b> is payable. By default an order is not billed until it is confirmed (a restaurant
 * can switch that off), and a cancelled order is not billed at all. Orders staff place without a guest are not part of any guest's bill.
 */
public final class BillMath {

    private BillMath() {
    }

    /** One order, with the payment figures the payment service worked out for it. */
    public record OrderFacts(
            long guestId,
            String guestName,
            OrderStatus status,
            BigDecimal settled,
            BigDecimal pending,
            BigDecimal outstanding) {
    }

    public record Amounts(
            BigDecimal due,
            BigDecimal inProgress,
            BigDecimal paid,
            BigDecimal awaitingConfirmation) {

        public static final Amounts ZERO = new Amounts(
                MoneyMath.money(BigDecimal.ZERO), MoneyMath.money(BigDecimal.ZERO),
                MoneyMath.money(BigDecimal.ZERO), MoneyMath.money(BigDecimal.ZERO));

        Amounts plus(Amounts o) {
            return new Amounts(
                    MoneyMath.money(due.add(o.due)),
                    MoneyMath.money(inProgress.add(o.inProgress)),
                    MoneyMath.money(paid.add(o.paid)),
                    MoneyMath.money(awaitingConfirmation.add(o.awaitingConfirmation)));
        }
    }

    public record GuestAmounts(long guestId, String guestName, Amounts amounts) {
    }

    /** Cancelled orders are owed by nobody. */
    public static boolean isBilled(OrderStatus status) {
        return status != OrderStatus.CANCELLED;
    }

    /**
     * Whether an order can be paid now. Never when cancelled. Not while PENDING either, when the
     * restaurant requires the kitchen to confirm orders first (the default; see BillingProperties).
     */
    public static boolean isPayable(OrderStatus status, boolean requireConfirmedOrders) {
        if (status == OrderStatus.CANCELLED) {
            return false;
        }
        return !requireConfirmedOrders || status != OrderStatus.PENDING;
    }

    /**
     * How far paid these amounts are. Money waiting for staff (inProgress) is not paid yet, and an
     * order awaiting the kitchen is still owed, so both keep a bill from reading as PAID.
     */
    public static TablePayState payState(Amounts a) {
        boolean owed = a.due().signum() > 0 || a.inProgress().signum() > 0 || a.awaitingConfirmation().signum() > 0;
        boolean received = a.paid().signum() > 0;
        if (!owed && !received) {
            return TablePayState.NO_ORDERS;
        }
        if (!owed) {
            return TablePayState.PAID;
        }
        return received ? TablePayState.PARTIALLY_PAID : TablePayState.UNPAID;
    }

    public static Amounts of(OrderFacts order, boolean requireConfirmedOrders) {
        if (!isBilled(order.status())) {
            return Amounts.ZERO;
        }
        boolean payable = isPayable(order.status(), requireConfirmedOrders);
        return new Amounts(
                MoneyMath.money(payable ? order.outstanding() : BigDecimal.ZERO),
                MoneyMath.money(order.pending()),
                MoneyMath.money(order.settled()),
                MoneyMath.money(payable ? BigDecimal.ZERO : order.outstanding()));
    }

    public static Amounts total(Collection<OrderFacts> orders, boolean requireConfirmedOrders) {
        Amounts sum = Amounts.ZERO;
        for (OrderFacts order : orders) {
            sum = sum.plus(of(order, requireConfirmedOrders));
        }
        return sum;
    }

    /**
     * Per guest, in the order each first appears. A guest whose orders were all cancelled does not
     * appear: they owe nothing and there is nothing to show about them.
     */
    public static List<GuestAmounts> byGuest(Collection<OrderFacts> orders, boolean requireConfirmedOrders) {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Amounts> sums = new LinkedHashMap<>();

        for (OrderFacts order : orders) {
            if (!isBilled(order.status())) {
                continue;
            }
            names.putIfAbsent(order.guestId(), order.guestName());
            sums.merge(order.guestId(), of(order, requireConfirmedOrders), Amounts::plus);
        }

        List<GuestAmounts> result = new ArrayList<>();
        sums.forEach((id, amounts) -> result.add(new GuestAmounts(id, names.get(id), amounts)));
        return result;
    }
}
