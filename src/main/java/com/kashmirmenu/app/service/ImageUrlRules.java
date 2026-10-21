package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * Which image addresses a dish may carry: one of our own uploaded photos, or an {@code https://} link.
 * Pure, so the rule is testable.
 *
 * <p>The server did not check this at all, so any text was accepted. An {@code http://} link is silently
 * blocked by the browser's security policy and shows as a missing photo, and nothing stops {@code javascript:}
 * or {@code data:} text being stored. The rule applies only when the address CHANGES: the edit form re-sends
 * the stored address with every save, and a restaurant with an older link must still be able to change a price.
 */
public final class ImageUrlRules {

    public static final int MAX_LENGTH = 500;

    private ImageUrlRules() {
    }

    /** What is wrong with the address, as a sentence for the owner, or null when it is fine or blank. */
    public static String problem(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.strip();
        if (u.length() > MAX_LENGTH) {
            return "The photo link is too long (limit " + MAX_LENGTH + " characters).";
        }
        if (u.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return "The photo link cannot contain spaces.";
        }
        if (u.startsWith(ImageKeys.PATH_PREFIX)) {
            return ImageKeys.keyFrom(u) == null ? "That is not a valid uploaded photo." : null;
        }
        if (!u.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "The photo link must start with https:// (or upload a photo instead).";
        }
        // Everything between "https://" and the first / ? or # is the authority (user info, host, port).
        String authority = u.substring("https://".length()).split("[/?#]", 2)[0];
        if (authority.isEmpty()) {
            return "The photo link is not a valid web address.";
        }
        if (authority.contains("@")) {
            return "The photo link must not contain a username or password.";
        }
        if (!hasUsableHost(u, authority)) {
            return "The photo link is not a valid web address.";
        }
        return null;
    }

    /**
     * Checks the address a request wants to set. An address identical to the one already stored is let
     * through unchecked so an unrelated edit is never blocked by an old link.
     */
    public static void requireValidIfChanged(String requested, String stored) {
        if (Objects.equals(normalize(requested), normalize(stored))) {
            return;
        }
        String problem = problem(requested);
        if (problem != null) {
            throw new BadRequestException(problem);
        }
    }

    /**
     * Whether the address has a real host. java.net.URI cannot read an internationalised domain name
     * (a host written in Devanagari, Arabic script or Kanji), yet browsers load them, so a host it cannot
     * read is retried in its ASCII (punycode) form before the address is refused.
     */
    private static boolean hasUsableHost(String url, String authority) {
        try {
            if (new URI(url).getHost() != null) {
                return true;
            }
        } catch (URISyntaxException ignored) {
            // fall through to the international form
        }
        try {
            String hostPart = authority;
            String portPart = "";
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && !authority.startsWith("[") && authority.indexOf(']') < 0) {
                hostPart = authority.substring(0, colon);
                portPart = authority.substring(colon);
            }
            String ascii = IDN.toASCII(hostPart, IDN.ALLOW_UNASSIGNED);
            String rest = url.substring("https://".length() + authority.length());
            return new URI("https://" + ascii + portPart + rest).getHost() != null;
        } catch (IllegalArgumentException | URISyntaxException e) {
            return false;
        }
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
