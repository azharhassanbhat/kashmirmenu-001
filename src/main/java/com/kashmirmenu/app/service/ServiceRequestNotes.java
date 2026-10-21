package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;

/**
 * The free-text note a guest may add to a service request. Pure, so it can be tested without Spring.
 * Trimmed, whitespace collapsed, control characters dropped; nothing left means no note.
 */
public final class ServiceRequestNotes {

    public static final int MAX_LENGTH = 200;

    private ServiceRequestNotes() {
    }

    /** The cleaned note, or null when nothing is left. Refuses (400) a note over {@link #MAX_LENGTH} characters. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_LENGTH) {
            throw new BadRequestException("Please keep the note under " + MAX_LENGTH + " characters.");
        }
        return cleaned;
    }
}
