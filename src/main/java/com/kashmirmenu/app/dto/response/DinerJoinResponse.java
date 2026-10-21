package com.kashmirmenu.app.dto.response;

/**
 * Returned once, when a guest joins. {@code dinerToken} is the guest's only credential: the client
 * keeps it and sends it in the X-Diner-Token header. It cannot be retrieved again.
 */
public record DinerJoinResponse(
        String dinerToken,
        String displayName,
        String visitId) {
}
