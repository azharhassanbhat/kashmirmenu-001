package com.kashmirmenu.app.dto.response;

/** The acknowledgment a guest sees after sending feedback. Deliberately carries nothing that was submitted. */
public record AnonymousFeedbackAckResponse(boolean received, String message) {
}
