package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.analytics.MoneyMath;
import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.request.CollectPaymentsRequest;
import com.kashmirmenu.app.dto.request.CreatePaymentRequest;
import com.kashmirmenu.app.dto.request.RefundRequest;
import com.kashmirmenu.app.dto.request.UpdatePaymentStatusRequest;
import com.kashmirmenu.app.dto.response.OrderPaymentSummaryResponse;
import com.kashmirmenu.app.dto.response.PaymentResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.PaymentRefund;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantPaymentSetting;
import com.kashmirmenu.app.entity.Settlement;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.CardType;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.mapper.PaymentMapper;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.PaymentRefundRepository;
import com.kashmirmenu.app.repository.PaymentRepository;
import com.kashmirmenu.app.repository.RestaurantPaymentSettingRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.NotificationService;
import com.kashmirmenu.app.service.PaymentService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.util.AfterCommit;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Payment rules. The invariants this class protects:
 * <ul>
 *   <li>Money is only ever BigDecimal; an order's paid/due figures are derived from its payment records.</li>
 *   <li>The order row (or payment row) is locked while it is changed, so two staff members, a double
 *       click or a repeated gateway callback cannot overpay an order or over-refund a payment.</li>
 *   <li>Only a PENDING payment can change status, and repeating the same change is a no-op.</li>
 *   <li>The UPI app / wallet is stored only when supplied and must belong to the payment method.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentServiceImpl implements PaymentService {

    private final NotificationService notificationService;
    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final PaymentMapper paymentMapper;
    private final RestaurantPaymentSettingRepository paymentSettingRepository;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;
    private final AuditService auditService;

    @Value("${app.currency:INR}")
    private String currency;

    // ------------------------------------------------------------------ creating payments

    @Override
    public PaymentResponse createPayment(Long orderId, CreatePaymentRequest request) {

        Order order = lockOrder(orderId);

        assertCurrentRestaurant(order.getRestaurant().getId(), "Order not found.");

        return createPending(order, request.getPaymentMethod(), false);
    }

    @Override
    public PaymentResponse createPaymentByPublicToken(String publicToken, CreatePaymentRequest request) {

        Order found = orderRepository.findByPublicToken(publicToken)
                .orElseThrow(() -> new EntityNotFoundException("Order not found."));

        return createPending(lockOrder(found.getId()), request.getPaymentMethod(), true);
    }

    @Override
    public Payment openPendingPayment(Order order, PaymentMethod method, BigDecimal amount, Settlement settlement) {

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("This order was cancelled.");
        }
        if (!method.isDinerSelectable()) {
            throw new IllegalArgumentException(method.label() + " cannot be chosen here. Please ask a member of staff.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("There is nothing to pay on this order.");
        }
        validatePaymentMethod(order, method);
        requireUpiQr(order, method);

        Payment payment = newPayment(order, method, amount, PaymentStatus.PENDING);
        payment.setSettlement(settlement);
        return paymentRepository.save(payment);
    }

    @Override
    public int cancelPendingPayments(Settlement settlement) {

        LocalDateTime now = businessTime.now();
        int cancelled = 0;

        // Ids first, then each row read under its own lock: a copy loaded earlier in this transaction
        // could be stale if staff confirmed the payment a moment ago.
        for (Long id : paymentRepository.findIdsBySettlement(settlement)) {
            Payment payment = paymentRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new EntityNotFoundException("Payment not found."));
            if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
                payment.setPaymentStatus(PaymentStatus.CANCELLED);
                payment.setPaymentTimestamp(now);
                paymentRepository.save(payment);
                cancelled++;
            }
        }
        return cancelled;
    }

    private PaymentResponse createPending(Order order, PaymentMethod method, boolean fromDiner) {

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("This order was cancelled.");
        }
        if (fromDiner && !method.isDinerSelectable()) {
            throw new IllegalArgumentException(method.label() + " cannot be chosen here. Please ask a member of staff.");
        }
        validatePaymentMethod(order, method);

        List<Payment> existing = paymentRepository.findByOrderIdOrderByIdAsc(order.getId());

        // One open request at a time. A failed or cancelled one does not block a retry.
        if (existing.stream().anyMatch(p -> p.getPaymentStatus() == PaymentStatus.PENDING)) {
            throw new IllegalStateException("Payment already exists for this order.");
        }

        PaymentBalance.Result balance = balanceOf(order, existing);
        if (balance.outstanding().signum() <= 0) {
            throw new IllegalStateException("This order is already fully paid.");
        }

        Payment payment = newPayment(order, method, balance.outstanding(), PaymentStatus.PENDING);
        payment = paymentRepository.save(payment);

        return paymentMapper.toResponse(payment, BigDecimal.ZERO);
    }

    @Override
    public OrderPaymentSummaryResponse collectPayments(Long orderId, CollectPaymentsRequest request) {

        Order order = lockOrder(orderId);
        assertCurrentRestaurant(order.getRestaurant().getId(), "Order not found.");

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("This order was cancelled.");
        }

        Long restaurantId = order.getRestaurant().getId();
        List<CollectPaymentsRequest.Component> parts = request.payments();
        String key = blankToNull(request.idempotencyKey());

        // A repeated request (double tap, retry after a timeout) returns what was already recorded.
        if (key != null) {
            List<String> keys = IntStream.range(0, parts.size()).mapToObj(i -> key + ":" + i).toList();
            List<Payment> replay = paymentRepository.findByRestaurantIdAndIdempotencyKeyIn(restaurantId, keys);
            if (!replay.isEmpty()) {
                boolean sameRequest = replay.size() == parts.size()
                        && replay.stream().allMatch(p -> p.getOrder().getId().equals(order.getId()));
                if (!sameRequest) {
                    throw new IllegalStateException("That request key was already used for a different payment.");
                }
                return summaryOf(order);
            }
        }

        for (CollectPaymentsRequest.Component c : parts) {
            validateInstrument(c.paymentMethod(), c.paymentProvider(), c.cardType());
        }

        LocalDateTime now = businessTime.now();
        User staff = currentUserService.getCurrentUser();

        // Taking payment at the counter supersedes whatever the diner picked on their phone. The old
        // request stays on record as CANCELLED; it never counted as revenue.
        List<Payment> existing = paymentRepository.findByOrderIdOrderByIdAsc(order.getId());
        for (Payment p : existing) {
            if (p.getPaymentStatus() == PaymentStatus.PENDING) {
                p.setPaymentStatus(PaymentStatus.CANCELLED);
                p.setPaymentTimestamp(now);
            }
        }

        PaymentBalance.Result balance = balanceOf(order, existing);
        BigDecimal requested = parts.stream()
                .map(CollectPaymentsRequest.Component::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (requested.compareTo(balance.outstanding()) > 0) {
            // Unchecked, so the transaction rolls back and the cancellation above is undone too.
            throw new IllegalArgumentException("The amounts add up to " + MoneyMath.format(requested, currency)
                    + ", but only " + MoneyMath.format(balance.outstanding(), currency) + " is due on this order.");
        }

        for (int i = 0; i < parts.size(); i++) {
            CollectPaymentsRequest.Component c = parts.get(i);
            Payment payment = newPayment(order, c.paymentMethod(), c.amount(), PaymentStatus.SUCCESS);
            payment.setPaymentProvider(c.paymentProvider());
            payment.setCardType(c.cardType());
            payment.setTransactionId(blankToNull(c.transactionId()));
            payment.setPaidAt(now);
            payment.setPaymentTimestamp(now);
            payment.setRecordedBy(staff);
            if (key != null) {
                payment.setIdempotencyKey(key + ":" + i);
            }
            paymentRepository.save(payment);
        }

        String collected = parts.size() + " payment(s) totalling " + MoneyMath.format(requested, currency) + " "
                + parts.stream().map(c -> c.paymentMethod().name()).distinct().toList();
        AfterCommit.run(() -> auditService.record(AuditAction.PAYMENT_COLLECTED, "ORDER", order.getId(), collected));

        notificationService.createNotification(
                order.getRestaurant(),
                order,
                "Payment Successful",
                "Payment of " + MoneyMath.format(requested, currency) + " received for Order " + order.getOrderNumber(),
                NotificationType.PAYMENT_SUCCESS);

        return summaryOf(order);
    }

    // ------------------------------------------------------------------ reading

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found."));

        assertCurrentRestaurant(payment.getRestaurant().getId(), "Payment not found.");

        return paymentMapper.toResponse(payment, refundRepository.sumByPaymentId(paymentId));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByOrder(Long orderId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found."));
        assertCurrentRestaurant(order.getRestaurant().getId(), "Payment not found.");

        List<Payment> payments = paymentRepository.findByOrderIdOrderByIdAsc(orderId);
        if (payments.isEmpty()) {
            throw new EntityNotFoundException("Payment not found.");
        }
        Payment latest = payments.get(payments.size() - 1);
        return paymentMapper.toResponse(latest, refundRepository.sumByPaymentId(latest.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentSummaryResponse getOrderPaymentSummary(Long orderId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found."));
        assertCurrentRestaurant(order.getRestaurant().getId(), "Order not found.");

        return summaryOf(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentBalance.Result getBalance(Order order) {
        return balanceOf(order, paymentRepository.findByOrderIdOrderByIdAsc(order.getId()));
    }

    // ------------------------------------------------------------------ changing status and refunding

    @Override
    public PaymentResponse updatePaymentStatus(Long paymentId, UpdatePaymentStatusRequest request) {

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found."));

        assertCurrentRestaurant(payment.getRestaurant().getId(), "Payment not found.");

        PaymentStatus target = request.getPaymentStatus();

        if (target == PaymentStatus.REFUNDED || target == PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalArgumentException("Refunds are recorded with the refund action, not by changing the status.");
        }
        if (target == PaymentStatus.PENDING) {
            throw new IllegalArgumentException("A payment cannot be moved back to pending.");
        }

        // A repeated callback or double click changes nothing and notifies nobody.
        if (payment.getPaymentStatus() == target) {
            return paymentMapper.toResponse(payment, refundRepository.sumByPaymentId(paymentId));
        }
        if (payment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("This payment is already "
                    + payment.getPaymentStatus().label().toLowerCase() + " and can no longer be changed.");
        }

        LocalDateTime now = businessTime.now();
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.setPaymentStatus(target);
        payment.setPaymentTimestamp(now);

        if (target == PaymentStatus.SUCCESS) {
            validateInstrument(payment.getPaymentMethod(), request.getPaymentProvider(), request.getCardType());
            if (request.getPaymentProvider() != null) {
                payment.setPaymentProvider(request.getPaymentProvider());
            }
            if (request.getCardType() != null) {
                payment.setCardType(request.getCardType());
            }
            String reference = blankToNull(request.getTransactionId());
            if (reference != null) {
                payment.setTransactionId(reference);
            }
            payment.setPaidAt(now);
            payment.setRecordedBy(currentUserService.getCurrentUser());
        }

        payment = paymentRepository.save(payment);

        // Who confirmed (or failed) a payment, and when, is recorded outside the payment row too.
        String changed = previousStatus + " -> " + target + ", " + payment.getPaymentMethod();
        AfterCommit.run(() -> auditService.record(AuditAction.PAYMENT_STATUS_CHANGED, "PAYMENT", paymentId, changed));

        Order order = payment.getOrder();
        if (target == PaymentStatus.SUCCESS) {

            notificationService.createNotification(
                    payment.getRestaurant(),
                    order,
                    "Payment Successful",
                    "Payment received successfully for Order " + order.getOrderNumber(),
                    NotificationType.PAYMENT_SUCCESS
            );

        } else if (target == PaymentStatus.FAILED) {

            notificationService.createNotification(
                    payment.getRestaurant(),
                    order,
                    "Payment Failed",
                    "Payment failed for Order " + order.getOrderNumber(),
                    NotificationType.PAYMENT_FAILED
            );
        }

        return paymentMapper.toResponse(payment, refundRepository.sumByPaymentId(paymentId));
    }

    @Override
    public PaymentResponse refundPayment(Long paymentId, RefundRequest request) {

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found."));

        assertCurrentRestaurant(payment.getRestaurant().getId(), "Payment not found.");

        if (!payment.getPaymentStatus().isCaptured()) {
            throw new IllegalStateException("Only a payment that was received can be refunded.");
        }

        BigDecimal alreadyRefunded = refundRepository.sumByPaymentId(paymentId);
        BigDecimal refundable = payment.getAmount().subtract(alreadyRefunded);

        if (request.amount().compareTo(refundable) > 0) {
            throw new IllegalArgumentException("Only " + MoneyMath.format(refundable, currency)
                    + " can still be refunded on this payment.");
        }

        PaymentRefund refund = new PaymentRefund();
        refund.setPayment(payment);
        refund.setAmount(request.amount());
        refund.setReason(blankToNull(request.reason()));
        refund.setRefundedAt(businessTime.now());
        refund.setRefundedBy(currentUserService.getCurrentUser());
        refundRepository.save(refund);

        BigDecimal totalRefunded = alreadyRefunded.add(request.amount());
        payment.setPaymentStatus(totalRefunded.compareTo(payment.getAmount()) >= 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED);
        paymentRepository.save(payment);

        String refunded = "Refunded " + MoneyMath.format(request.amount(), currency) + " of "
                + MoneyMath.format(payment.getAmount(), currency);
        AfterCommit.run(() -> auditService.record(AuditAction.PAYMENT_REFUNDED, "PAYMENT", paymentId, refunded));

        Order refundedOrder = payment.getOrder();
        notificationService.createNotification(
                payment.getRestaurant(),
                refundedOrder,
                "Refund Processed",
                "Refund of " + MoneyMath.format(request.amount(), currency)
                        + (refundedOrder != null ? " processed for Order " + refundedOrder.getOrderNumber() : " processed"),
                NotificationType.PAYMENT_REFUNDED);

        return paymentMapper.toResponse(payment, totalRefunded);
    }

    @Override
    public int cancelPendingPaymentsForOrder(Order order) {

        LocalDateTime now = businessTime.now();
        int cancelled = 0;

        for (Payment payment : paymentRepository.findByOrderIdOrderByIdAsc(order.getId())) {
            if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
                payment.setPaymentStatus(PaymentStatus.CANCELLED);
                payment.setPaymentTimestamp(now);
                paymentRepository.save(payment);
                cancelled++;
            }
        }
        return cancelled;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCapturedPayments(Order order) {

        return paymentRepository.findByOrderIdOrderByIdAsc(order.getId())
                .stream()
                .anyMatch(p -> p.getPaymentStatus().isCaptured());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PaymentBalance.Result> getBalances(Collection<Order> orders) {

        Map<Long, PaymentBalance.Result> balances = new HashMap<>();
        if (orders == null || orders.isEmpty()) {
            return balances;
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();

        // Two queries for the whole batch: the payments, then the refund totals for those payments.
        List<Payment> payments = paymentRepository.findByOrderIdInOrderByIdAsc(orderIds);
        Map<Long, BigDecimal> refunded = refundedByPayment(payments);

        Map<Long, List<Payment>> byOrder = new HashMap<>();
        for (Payment payment : payments) {
            byOrder.computeIfAbsent(payment.getOrder().getId(), k -> new java.util.ArrayList<>())
                    .add(payment);
        }

        for (Order order : orders) {
            List<Payment> own = byOrder.getOrDefault(order.getId(), List.of());
            balances.put(order.getId(),
                    PaymentBalance.of(order.getTotal(), linesOf(own, refunded)));
        }
        return balances;
    }

    // ------------------------------------------------------------------ helpers

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found."));
    }

    private Payment newPayment(Order order, PaymentMethod method, BigDecimal amount, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setRestaurant(order.getRestaurant());
        payment.setPaymentMethod(method);
        payment.setPaymentStatus(status);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setPaymentTimestamp(businessTime.now());
        return payment;
    }

    private void assertCurrentRestaurant(Long restaurantId, String notFoundMessage) {

        Restaurant current = currentUserService.getCurrentRestaurant();

        // Same answer for "not yours" and "does not exist", so ids cannot be probed across restaurants.
        if (current == null || !current.getId().equals(restaurantId)) {
            throw new EntityNotFoundException(notFoundMessage);
        }
    }

    /** UPI apps and wallets belong to their method; a card type only to CARD. Nothing is ever inferred. */
    private void validateInstrument(PaymentMethod method, PaymentProvider provider, CardType cardType) {

        if (provider != null && provider.method() != method) {
            throw new IllegalArgumentException(
                    provider.label() + " is not a " + method.label() + " provider.");
        }
        if (cardType != null && method != PaymentMethod.CARD) {
            throw new IllegalArgumentException("A card type only applies to card payments.");
        }
    }

    /**
     * A guest paying by UPI is shown the owner's uploaded QR, so UPI cannot be started without one: no
     * broken image, no placeholder. (Staff recording a UPI payment at the counter is not affected.)
     */
    private void requireUpiQr(Order order, PaymentMethod method) {
        if (method != PaymentMethod.UPI) {
            return;
        }
        boolean available = paymentSettingRepository
                .findByRestaurantId(order.getRestaurant().getId())
                .map(s -> com.kashmirmenu.app.service.PublicOrderingService.upiPayment(s) != null)
                .orElse(false);
        if (!available) {
            throw new IllegalArgumentException("UPI payment is currently unavailable for this restaurant.");
        }
    }

    private void validatePaymentMethod(Order order, PaymentMethod paymentMethod) {

        RestaurantPaymentSetting paymentSetting =
                paymentSettingRepository
                        .findByRestaurantId(order.getRestaurant().getId())
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Restaurant payment settings not found."));

        if (!paymentSetting.isPaymentMethodEnabled(paymentMethod)) {
            throw new IllegalArgumentException(
                    paymentMethod + " payment is disabled for this restaurant.");
        }
    }

    private List<PaymentBalance.Line> linesOf(List<Payment> payments, Map<Long, BigDecimal> refunded) {
        return payments.stream()
                .map(p -> new PaymentBalance.Line(
                        p.getPaymentStatus(), p.getAmount(), refunded.getOrDefault(p.getId(), BigDecimal.ZERO)))
                .toList();
    }

    private PaymentBalance.Result balanceOf(Order order, List<Payment> payments) {
        return PaymentBalance.of(order.getTotal(), linesOf(payments, refundedByPayment(payments)));
    }

    private OrderPaymentSummaryResponse summaryOf(Order order) {
        List<Payment> payments = paymentRepository.findByOrderIdOrderByIdAsc(order.getId());
        Map<Long, BigDecimal> refunded = refundedByPayment(payments);

        PaymentBalance.Result balance = PaymentBalance.of(order.getTotal(), linesOf(payments, refunded));

        List<PaymentResponse> responses = payments.stream()
                .map(p -> paymentMapper.toResponse(p, refunded.getOrDefault(p.getId(), BigDecimal.ZERO)))
                .toList();

        return new OrderPaymentSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getTotal(),
                balance.settled(),
                balance.pending(),
                balance.outstanding(),
                balance.status(),
                responses);
    }

    private Map<Long, BigDecimal> refundedByPayment(List<Payment> payments) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (payments.isEmpty()) {
            return out;
        }
        List<Long> ids = payments.stream().map(Payment::getId).toList();
        for (Object[] row : refundRepository.sumByPaymentIds(ids)) {
            out.put((Long) row[0], (BigDecimal) row[1]);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
