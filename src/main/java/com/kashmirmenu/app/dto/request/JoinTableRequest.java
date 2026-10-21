package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A guest joining a table: just a name. The service tidies and checks it further. */
public record JoinTableRequest(
        @NotBlank(message = "Please enter your name.")
        @Size(max = 100, message = "Name is too long.")
        String name) {
}
