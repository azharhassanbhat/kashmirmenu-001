package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotNull;

/** Mark one add-on or portion as available or sold out, without editing the whole set. */
public record UpdateOptionAvailabilityRequest(@NotNull Boolean available) {
}
