package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCartRequest {

    @NotBlank(message = "Table QR identifier is required")
    private String tableQrIdentifier;
}