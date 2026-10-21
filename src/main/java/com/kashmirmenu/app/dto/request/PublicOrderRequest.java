package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Size;

public record PublicOrderRequest(
        @Size(max = 255) String notes) {
}
