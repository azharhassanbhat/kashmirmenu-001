package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.ServiceRequestStatus;
import jakarta.validation.constraints.NotNull;

/** Staff moving a request along its lifecycle. */
public record UpdateServiceRequestStatusRequest(@NotNull ServiceRequestStatus status) {
}
