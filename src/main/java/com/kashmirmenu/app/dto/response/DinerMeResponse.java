package com.kashmirmenu.app.dto.response;

/** Who the current token belongs to. Never contains the token itself. */
public record DinerMeResponse(
        String displayName,
        String visitId) {
}
