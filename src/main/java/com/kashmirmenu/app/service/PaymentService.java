package com.kashmirmenu.app.service;

import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.dto.request.CollectPaymentsRequest;
import com.kashmirmenu.app.dto.request.CreatePaymentRequest;
import com.kashmirmenu.app.dto.request.RefundRequest;
import com.kashmirmenu.app.dto.request.UpdatePaymentStatusRequest;
import com.kashmirmenu.app.dto.response.OrderPaymentSummaryResponse;
import com.kashmirmenu.app.dto.response.PaymentResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Settlement;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.enums.PaymentMethod;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public interface PaymentService {

    /** Staff: records the chosen method as a PENDING payment for what is still due on the order. */
    PaymentResponse createPayment(
            Long orderId,
            CreatePaymentRequest request
    );

    /**
     * Diner (QR flow), identified by the order's unguessable public token rather than its number:
     * the endpoint is unauthenticated and order numbers are sequential. Only diner-selectable
     * payment methods are accepted.
     */
    PaymentResponse createPaymentByPublicToken(
            String publicToken,
            CreatePaymentRequest request);

    /**
     * Records one PENDING payment on an order as part of a settlement. The caller must already hold the
     * order's row lock and must have worked out {@code amount} from the order's balance under that lock.
     * The method must be one a diner may choose, and enabled for the restaurant.
     */
    Payment openPendingPayment(Order lockedOrder, PaymentMethod method, BigDecimal amount, Settlement settlement);

    /**
     * Cancels every payment of the settlement that is still PENDING, locking each payment row first, and
     * returns how many it cancelled. Payments already settled, failed or cancelled are left alone.
     * For the paying guest withdrawing their own attempt; staff go through updatePaymentStatus.
     */
    int cancelPendingPayments(Settlement settlement);

    /** Staff: records money received at the counter as one or more components (split bill). */
    OrderPaymentSummaryResponse collectPayments(
            Long orderId,
            CollectPaymentsRequest request);

    PaymentResponse getPayment(
            Long paymentId
    );

    /** The order's most recent payment (kept for existing clients; see getOrderPaymentSummary). */
    PaymentResponse getPaymentByOrder(
            Long orderId
    );

    OrderPaymentSummaryResponse getOrderPaymentSummary(
            Long orderId
    );

    PaymentResponse updatePaymentStatus(
            Long paymentId,
            UpdatePaymentStatusRequest request
    );

    PaymentResponse refundPayment(
            Long paymentId,
            RefundRequest request
    );

    /** Paid / pending / still due for an order, derived from its payment records. */
    PaymentBalance.Result getBalance(Order order);

    /**
     * Cancels any PENDING payments on an order, for use when the order itself is cancelled.
     *
     * <p>Without this, a diner's "I'll pay cash" choice outlived the order it belonged to and sat in
     * the reconciliation panel's awaiting-confirmation bucket for ever, overstating the money
     * expected at the close.
     *
     * @return how many payments were cancelled
     */
    int cancelPendingPaymentsForOrder(Order order);

    /** True when any payment on the order has been received (and so cannot simply be abandoned). */
    boolean hasCapturedPayments(Order order);

    /**
     * Balances for several orders at once, keyed by order id.
     *
     * <p>Exists so a list of orders costs two queries instead of two per row. Calling
     * {@link #getBalance(Order)} in a loop is what the Orders page was effectively doing from the
     * browser, one HTTP request per row.
     */
    Map<Long, PaymentBalance.Result> getBalances(Collection<Order> orders);
}
