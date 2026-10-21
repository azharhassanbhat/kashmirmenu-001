package com.kashmirmenu.app.platform;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sets the first password on an unclaimed platform owner account. Used once. */
public record PlatformClaimRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(max = 100) String claimToken,
        // Longer than the usual minimum: this is the most privileged account on the platform.
        @NotBlank @Size(min = 12, max = 100) String password) {
}
