package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Size;

/** A guest saying they paid by UPI. The reference is optional and checked by the service; no amount is accepted. */
public record ClaimPaidRequest(@Size(max = 60, message = "That reference is too long.") String reference) {
}
