package com.kashmirmenu.app.dto.response;

import java.time.LocalDateTime;

public record AnonymousFeedbackEntryResponse(
        Long id,
        int rating,
        String category,
        String comment,
        String contact,
        String orderNumber,
        LocalDateTime createdAt) {
}
