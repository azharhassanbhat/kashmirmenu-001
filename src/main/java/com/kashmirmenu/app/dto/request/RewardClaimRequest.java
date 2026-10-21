package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Asks for the points on one served order, named by its public token (the guest's own credential for it). */
public record RewardClaimRequest(
        @NotBlank(message = "Choose the order to add points for.") @Size(max = 100) String orderToken) {
}
