package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.ServiceRequestType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A guest asking for something at the table. */
public record CreateServiceRequestRequest(
        @NotNull(message = "Please choose what you need.") ServiceRequestType type,
        @Size(max = 200, message = "Please keep the note under 200 characters.") String note) {
}
