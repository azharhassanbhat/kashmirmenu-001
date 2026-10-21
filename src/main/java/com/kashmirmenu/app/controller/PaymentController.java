package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.CollectPaymentsRequest;
import com.kashmirmenu.app.dto.request.CreatePaymentRequest;
import com.kashmirmenu.app.dto.request.RefundRequest;
import com.kashmirmenu.app.dto.request.UpdatePaymentStatusRequest;
import com.kashmirmenu.app.dto.response.OrderPaymentSummaryResponse;
import com.kashmirmenu.app.dto.response.PaymentResponse;
import com.kashmirmenu.app.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CASHIER','WAITER')")
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create payment for an order")
    public PaymentResponse createPayment(
            @PathVariable Long orderId,
            @Valid @RequestBody CreatePaymentRequest request) {

        return paymentService.createPayment(orderId, request);
    }

    @PostMapping("/orders/{orderId}/collect")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record money received at the counter, as one or more components (split bill)")
    public OrderPaymentSummaryResponse collectPayments(
            @PathVariable Long orderId,
            @Valid @RequestBody CollectPaymentsRequest request) {

        return paymentService.collectPayments(orderId, request);
    }

    @GetMapping("/orders/{orderId}/summary")
    @Operation(summary = "Paid, pending and still-due amounts for an order, with every payment on it")
    public OrderPaymentSummaryResponse getOrderPaymentSummary(
            @PathVariable Long orderId) {

        return paymentService.getOrderPaymentSummary(orderId);
    }

    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Refund part or all of a payment (managers only)")
    public PaymentResponse refundPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody RefundRequest request) {

        return paymentService.refundPayment(paymentId, request);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment by id")
    public PaymentResponse getPayment(
            @PathVariable Long paymentId) {

        return paymentService.getPayment(paymentId);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get the most recent payment of an order (see /summary for all of them)")
    public PaymentResponse getPaymentByOrder(
            @PathVariable Long orderId) {

        return paymentService.getPaymentByOrder(orderId);
    }

    @PutMapping("/{paymentId}/status")
    @Operation(summary = "Update payment status")
    public PaymentResponse updatePaymentStatus(
            @PathVariable Long paymentId,
            @Valid @RequestBody UpdatePaymentStatusRequest request) {

        return paymentService.updatePaymentStatus(paymentId, request);
    }
}