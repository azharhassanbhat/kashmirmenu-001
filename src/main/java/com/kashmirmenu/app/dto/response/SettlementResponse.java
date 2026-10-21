package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.SettlementKind;
import com.kashmirmenu.app.enums.SettlementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A settlement as its paying guest sees it. {@code total} is what was asked for; {@code pending} is
 * what staff have yet to confirm and {@code confirmed} what they have. Nothing is paid until staff
 * confirm it, so {@code status} stays PENDING until then.
 */
public record SettlementResponse(
        String code,
        SettlementKind kind,
        SettlementStatus status,
        PaymentMethod paymentMethod,
        BigDecimal total,
        BigDecimal pending,
        BigDecimal confirmed,
        LocalDateTime createdAt,
        /** When staff confirmed the money (the latest of its payments), or null while nothing is confirmed. */
        LocalDateTime confirmedAt,
        /** The receipt or transaction reference staff recorded when confirming, or null if none was given. */
        String reference,
        /** When the guest said they had paid (UPI), or null. Staff have not confirmed anything yet. */
        LocalDateTime customerConfirmedAt,
        List<SettlementGuestLine> guests) {
}
