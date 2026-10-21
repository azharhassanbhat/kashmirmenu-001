package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new password for a staff member who has forgotten theirs. They are signed out everywhere. */
public record ResetStaffPasswordRequest(
        @NotBlank(message = "Please enter a password.")
        @Size(max = 100, message = "The password must be at most 100 characters.") String password) {
}
