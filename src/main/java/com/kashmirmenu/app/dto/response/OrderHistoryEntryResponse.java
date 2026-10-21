package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.CancelReason;
import com.kashmirmenu.app.enums.OrderStatus;

import java.time.LocalDateTime;

/** One step in an order's history. {@code from} is null for the order being placed. */
public record OrderHistoryEntryResponse(
        OrderStatus from,
        OrderStatus to,
        String actor,
        CancelReason reason,
        String reasonLabel,
        String note,
        LocalDateTime at) {
}
