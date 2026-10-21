package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.response.PaymentResponse;
import com.kashmirmenu.app.entity.Payment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Hand-written (rather than MapStruct) because the response carries a computed value, the refunded
 * total, that is not a property of the entity.
 */
@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment payment, BigDecimal refundedAmount) {
        PaymentResponse r = new PaymentResponse();
        r.setId(payment.getId());
        r.setOrderId(payment.getOrder().getId());
        r.setPaymentMethod(payment.getPaymentMethod());
        r.setPaymentProvider(payment.getPaymentProvider());
        r.setCardType(payment.getCardType());
        r.setPaymentStatus(payment.getPaymentStatus());
        r.setTransactionId(payment.getTransactionId());
        r.setAmount(payment.getAmount());
        r.setRefundedAmount(refundedAmount == null ? BigDecimal.ZERO : refundedAmount);
        r.setPaidAt(payment.getPaidAt());
        r.setPaymentTimestamp(payment.getPaymentTimestamp());
        return r;
    }
}
