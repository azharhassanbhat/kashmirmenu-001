package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.CancelReason;
import com.kashmirmenu.app.exception.BadRequestException;

import java.util.Locale;

/**
 * What must accompany the cancellation of an order. Pure, so the rules are testable.
 *
 * <p>The reason is OPTIONAL at the API: callers that predate reasons, and every existing test, cancel
 * without one and still work (the order simply records no reason). The screens always ask. When a
 * reason IS given it must be on the list, and {@code OTHER} needs a note saying what it was.
 */
public final class CancellationRules {

    private CancellationRules() {
    }

    /** The validated reason (null when none was given) and the cleaned note (null when empty). */
    public record Cancellation(CancelReason reason, String note) {
    }

    public static Cancellation validate(String reasonCode, String rawNote) {
        String note = ServiceRequestNotes.clean(rawNote);   // trims, drops control characters, caps at 200
        CancelReason reason = parse(reasonCode);
        if (reason == CancelReason.OTHER && note == null) {
            throw new BadRequestException("Please say what the other reason was.");
        }
        return new Cancellation(reason, note);
    }

    /** null or blank means no reason was given; anything else must be one of the listed codes. */
    public static CancelReason parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return CancelReason.valueOf(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("That is not a recognised reason for cancelling.");
        }
    }
}
