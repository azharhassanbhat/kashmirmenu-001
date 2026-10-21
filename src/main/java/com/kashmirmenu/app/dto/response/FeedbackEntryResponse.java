package com.kashmirmenu.app.dto.response;

import java.time.LocalDateTime;

/** One line of the owner's feedback list. No guest name or contact is held, so none is shown. */
public record FeedbackEntryResponse(
        Long id,
        String orderNumber,
        String tableName,
        int rating,
        String comment,
        LocalDateTime createdAt) {
}
