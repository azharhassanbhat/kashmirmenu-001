package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Staff redeeming one reward for the member whose code the guest read out. */
public record RewardRedeemRequest(
        @NotBlank(message = "Enter the member's code.") @Size(max = 40) String memberCode) {
}
