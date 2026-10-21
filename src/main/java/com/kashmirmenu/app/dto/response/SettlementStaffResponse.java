package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.SettlementKind;
import com.kashmirmenu.app.enums.SettlementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** A settlement as staff see it: who is paying, for which orders, and what is waiting to be confirmed. */
public record SettlementStaffResponse(
        Long id,
        String code,
        SettlementKind kind,
        SettlementStatus status,
        String tableName,
        String payerName,
        PaymentMethod paymentMethod,
        BigDecimal total,
        BigDecimal pending,
        BigDecimal confirmed,
        LocalDateTime createdAt,
        /** When the guest claimed to have paid, or null if they have not (yet). */
        LocalDateTime customerConfirmedAt,
        /** The UPI reference the guest typed in, or null. Check it against the bank statement. */
        String customerReference,
        List<SettlementOrderLine> orders) {
}
