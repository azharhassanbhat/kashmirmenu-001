package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new staff account. The person setting it up chooses the first password and hands it over. */
public record CreateStaffRequest(
        @NotBlank(message = "Please enter the person's name.")
        @Size(max = 100, message = "The name can be up to 100 characters.") String name,
        @NotBlank(message = "Please enter an email address.")
        @Email(message = "Please enter a valid email address.")
        @Size(max = 150, message = "The email can be up to 150 characters.") String email,
        @NotNull(message = "Please choose a role.") RoleName role,
        @NotBlank(message = "Please enter a password.")
        @Size(max = 100, message = "The password must be at most 100 characters.") String password) {
}
