package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names and secrets for guests at a table. Pure functions, so the rules are testable without a
 * database.
 */
public final class DinerIdentity {

    public static final int MAX_NAME_LENGTH = 40;

    /**
     * Letters (any script, so Hindi, Urdu and Kashmiri names work), combining marks, digits, spaces
     * and a little punctuation. Deliberately not parentheses: "(2)" is how duplicates are told
     * apart, and a guest should not be able to pass as one. Anything else (control and invisible
     * characters, emoji, markup) is refused rather than silently altered.
     */
    private static final Pattern ALLOWED_NAME = Pattern.compile("^[\\p{L}\\p{M}\\p{N} '.\\-_&,]+$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private DinerIdentity() {
    }

    /** Trims, collapses runs of whitespace, and checks length and characters. */
    public static String normalizeName(String raw) {
        // strip(), not trim(): trim() also removes control characters at the ends, which would let
        // a name like "Rahul\u0000" through instead of being refused.
        String name = raw == null ? "" : raw.replaceAll("\\s+", " ").strip();

        if (name.isEmpty()) {
            throw new BadRequestException("Please enter your name.");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new BadRequestException("Please use " + MAX_NAME_LENGTH + " characters or fewer for your name.");
        }
        if (!ALLOWED_NAME.matcher(name).matches()) {
            throw new BadRequestException("Names can use letters, numbers and basic punctuation only.");
        }
        return name;
    }

    /**
     * The name to show for a new guest: the name as typed if nobody at the table has it, otherwise
     * "Name (2)", "Name (3)" and so on. Comparison ignores case, so "rahul" and "Rahul" are the
     * same name.
     */
    public static String uniqueDisplayName(String name, Collection<String> taken) {
        Set<String> lowered = new HashSet<>();
        for (String t : taken) {
            lowered.add(t.toLowerCase(Locale.ROOT));
        }
        if (!lowered.contains(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        for (int n = 2; ; n++) {
            String candidate = name + " (" + n + ")";
            if (!lowered.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    /** 256 random bits, URL-safe, 43 characters. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 as lowercase hex. What is stored instead of the token. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException(e);
        }
    }
}
