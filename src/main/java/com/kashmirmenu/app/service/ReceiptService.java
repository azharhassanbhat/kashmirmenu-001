package com.kashmirmenu.app.service;

import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.dto.response.ReceiptResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderItem;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.PaymentRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * A printable bill for an order. Read-only: it assembles what the order recorded (items with their options,
 * the totals and rates it was billed at, and the payments actually received) and the restaurant's details.
 * Nothing is recalculated.
 *
 * <p>A guest reaches it with their order's random token, exactly as for tracking the order; staff reach it by
 * order id, scoped to their own restaurant (another restaurant's order answers "not found").
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final BillingSettingsService billingSettings;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public ReceiptResponse forToken(String publicToken) {
        Order order = orders.findByPublicToken(publicToken)
                // Deliberately bare: a message naming the token would confirm which values exist.
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return build(order);
    }

    @Transactional(readOnly = true)
    public ReceiptResponse forOrderId(Long orderId) {
        Order order = orders.findByIdAndRestaurant(orderId, currentUserService.getCurrentRestaurant())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return build(order);
    }

    private ReceiptResponse build(Order order) {
        ReceiptRules.requireReceiptable(order.getStatus());

        Restaurant r = order.getRestaurant();
        ReceiptResponse.Restaurant header = new ReceiptResponse.Restaurant(
                r.getName(), r.getAddress(), r.getCity(), r.getState(), r.getPhone(),
                billingSettings.taxRegistrationFor(r.getId()));

        List<ReceiptResponse.Item> items = order.getItems().stream().map(ReceiptService::toItem).toList();

        List<Payment> received = payments.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .filter(p -> p.getPaymentStatus() != null && p.getPaymentStatus().isCaptured())
                .toList();
        BigDecimal captured = received.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentBalance.Result balance = paymentService.getBalance(order);
        BigDecimal refunded = ReceiptRules.refunded(captured, balance.settled());
        BigDecimal owed = balance.outstanding().signum() < 0 ? BigDecimal.ZERO : balance.outstanding();

        return new ReceiptResponse(
                header,
                order.getOrderNumber(),
                order.getRestaurantTable() == null ? null : order.getRestaurantTable().getTableName(),
                order.getCreatedAt(),
                items,
                order.getSubtotal(),
                order.getDiscount(),
                order.getCouponCode(),
                order.getServiceCharge(),
                order.getServiceChargeRate(),
                order.getTax(),
                order.getTaxName(),
                order.getTaxRate(),
                order.getTaxIncluded(),
                Boolean.TRUE.equals(order.getPricesIncludeTax()),
                order.getTotal(),
                received.stream().map(ReceiptService::toPayment).toList(),
                balance.settled(),
                refunded,
                owed,
                ReceiptRules.heading(order.getTotal(), balance.settled(), balance.outstanding(), refunded));
    }

    private static ReceiptResponse.Item toItem(OrderItem i) {
        return new ReceiptResponse.Item(
                i.getItemName(),
                i.getQuantity() == null ? 0 : i.getQuantity(),
                i.getUnitPrice(),
                i.getLineTotal(),
                i.getOptions().stream().map(o -> o.getGroupName() + ": " + o.getOptionName()).toList());
    }

    private static ReceiptResponse.Payment toPayment(Payment p) {
        return new ReceiptResponse.Payment(
                ReceiptRules.methodLabel(p.getPaymentMethod(), p.getPaymentProvider()),
                p.getAmount(),
                p.getPaymentTimestamp() != null ? p.getPaymentTimestamp() : p.getPaidAt());
    }
}
