package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row of the transaction history table. */
public record PaymentTransactionResponse(
        Long id,
        /** The gateway reference when one was recorded, otherwise our own PAY-<id>. */
        String reference,
        boolean hasGatewayReference,
        Long orderId,
        String orderNumber,
        String method,
        String methodLabel,
        /** UPI app / wallet key, or UNKNOWN_UPI / UNKNOWN_WALLET, or null for methods without providers. */
        String provider,
        String providerLabel,
        String cardType,
        BigDecimal amount,
        BigDecimal refundedAmount,
        String status,
        String statusLabel,
        LocalDateTime timestamp,
        String recordedBy,
        LocalDateTime settledAt,
        String currency) {
}
