package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The tax registration number a restaurant prints on its receipts (a GSTIN in India, a VAT number elsewhere).
 * Pure, so the rule is testable.
 *
 * <p>Deliberately NOT a check that the number is genuine or correctly formed for any one country: that needs
 * the tax authority's records, and a rule tuned to one country would wrongly refuse another's. This only keeps
 * the field tidy and safe to print: upper case, no spaces, letters, digits, hyphen and slash, 5 to 30 long.
 * Blank means none. Whether the number is the right one is the owner's responsibility.
 */
public final class TaxRegistration {

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 30;
    private static final Pattern ALLOWED = Pattern.compile("[A-Z0-9/-]+");

    private TaxRegistration() {
    }

    /** The number tidied for printing, or null when blank. Refuses (400) anything that cannot be a registration number. */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // Every kind of space goes, not just the ASCII ones: a number copied from a web page, a PDF or WhatsApp
        // often carries a non-breaking space or a byte-order mark, and the screen (JavaScript) already tidies those.
        String tidy = raw.replaceAll("[\\s\\p{Z}\\uFEFF]+", "").toUpperCase(Locale.ROOT);
        if (tidy.length() < MIN_LENGTH || tidy.length() > MAX_LENGTH || !ALLOWED.matcher(tidy).matches()) {
            throw new BadRequestException("A tax registration number is " + MIN_LENGTH + " to " + MAX_LENGTH
                    + " letters, digits, hyphens or slashes, for example 29ABCDE1234F1Z5.");
        }
        return tidy;
    }
}
