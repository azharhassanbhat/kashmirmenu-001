package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation of the external links a restaurant puts on its landing page. Pure, so every rule is testable.
 *
 * <p>Every link must be an absolute {@code https} address with a real host, no embedded credentials and no
 * whitespace or control characters, and at most {@value #MAX_LENGTH} characters. Blank means "not set" and is
 * returned as {@code null}, so the screen hides the action instead of rendering a broken link.
 *
 * <p>The Google review link is the one a restaurant copies from Google Business Profile ("Get more reviews"). It
 * is stored exactly as given and is never built from a Place ID. Only Google's own review and Maps address
 * shapes are accepted, so the button can never be pointed at an arbitrary site or at a Google redirector such as
 * {@code google.com/url?q=...}.
 */
public final class LandingLinks {

    public static final int MAX_LENGTH = 500;

    private static final Pattern GOOGLE_HOST =
            Pattern.compile("^(www\\.|maps\\.)?google\\.(com|co\\.[a-z]{2}|com\\.[a-z]{2}|[a-z]{2})$");

    /** Paths on google.* that lead to a place or its review form. Deliberately excludes /url and /amp. */
    private static final List<String> GOOGLE_PATHS = List.of("/maps", "/search", "/local/writereview", "/g/");

    private static final Pattern G_PAGE_PATH = Pattern.compile("^/(r/)?[A-Za-z0-9_.-]+/review/?$");
    /** A short link is one opaque code: /AbCdEf123 (with an optional trailing slash). */
    private static final Pattern SHORT_CODE = Pattern.compile("^/[A-Za-z0-9_-]{3,}/?$");

    private static final List<String> INSTAGRAM = List.of("instagram.com", "www.instagram.com", "instagr.am");
    private static final List<String> YOUTUBE =
            List.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be");
    private static final List<String> FACEBOOK =
            List.of("facebook.com", "www.facebook.com", "m.facebook.com", "fb.com", "www.fb.com", "fb.me");

    private LandingLinks() {
    }

    /** Any public https address, or null when blank. Used for the website and the map link. */
    public static String https(String raw, String what) {
        return parse(raw, what).map(URI::toString).orElse(null);
    }

    public static String instagram(String raw) {
        return onHosts(raw, "Instagram link", INSTAGRAM);
    }

    public static String youtube(String raw) {
        return onHosts(raw, "YouTube link", YOUTUBE);
    }

    public static String facebook(String raw) {
        return onHosts(raw, "Facebook link", FACEBOOK);
    }

    /**
     * A direct Google review link, or null when blank. Accepted shapes:
     * <ul>
     *   <li>{@code https://g.page/r/<id>/review} (Google Business Profile's share link)</li>
     *   <li>{@code https://search.google.com/local/writereview?placeid=...}</li>
     *   <li>{@code https://www.google.com/maps/...}, {@code /search...}, {@code /local/writereview...} on
     *       google.com, maps.google.com and country domains such as google.co.in</li>
     *   <li>{@code https://maps.app.goo.gl/<code>} and {@code https://goo.gl/maps/<code>} short links</li>
     * </ul>
     */
    public static String googleReview(String raw) {
        URI uri = parse(raw, "Google review link").orElse(null);
        if (uri == null) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();

        // Browsers resolve "/maps/../url" to "/url" and treat %2e / %2f as . and /, so a path that merely STARTS with
        // an allowed prefix could still land on Google's redirector. Any dot-segment, encoded dot/slash/backslash
        // or doubled slash is refused outright.
        boolean ok = cleanPath(path);
        if (!ok) {
            // fall through to the refusal below
        } else if (host.equals("g.page")) {
            // g.page/r/<id>/review (the share link) or the older g.page/<name>/review
            ok = G_PAGE_PATH.matcher(path).matches();
        } else if (host.equals("search.google.com")) {
            ok = path.startsWith("/local/writereview");
        } else if (host.equals("maps.app.goo.gl")) {
            ok = SHORT_CODE.matcher(path).matches();
        } else if (host.equals("goo.gl")) {
            ok = path.startsWith("/maps/") && SHORT_CODE.matcher(path.substring("/maps".length())).matches();
        } else if (GOOGLE_HOST.matcher(host).matches()) {
            ok = GOOGLE_PATHS.stream().anyMatch(path::startsWith);
        } else {
            ok = false;
        }
        // "I'm feeling lucky" (btnI) sends the visitor to the first search result, i.e. a site we never checked.
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
        if (ok && java.util.Arrays.stream(query.split("&")).anyMatch(p -> p.equals("btni") || p.startsWith("btni="))) {
            ok = false;
        }
        if (!ok) {
            throw new BadRequestException(
                    "Enter the review link from your Google Business Profile, such as https://g.page/r/.../review.");
        }
        return uri.toString();
    }

    /** False for dot-segments, encoded dots/slashes/backslashes and doubled slashes anywhere in the path. */
    private static boolean cleanPath(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("//")) {
            return false;
        }
        for (String segment : lower.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** True when the stored value is still a safe Google review link (for output, as a second line of defence). */
    public static boolean isSafeGoogleReview(String stored) {
        try {
            return googleReview(stored) != null;
        } catch (BadRequestException e) {
            return false;
        }
    }

    private static String onHosts(String raw, String what, List<String> hosts) {
        URI uri = parse(raw, what).orElse(null);
        if (uri == null) {
            return null;
        }
        if (!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("Enter a valid " + what + " (it must be on " + hosts.get(0) + ").");
        }
        return uri.toString();
    }

    private static java.util.Optional<URI> parse(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String value = raw.strip();
        if (value.length() > MAX_LENGTH) {
            throw new BadRequestException("The " + what + " is too long (at most " + MAX_LENGTH + " characters).");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\' || c == '<' || c == '>'
                    || c == '"' || c == '\'' || c == '`') {
                throw new BadRequestException("The " + what + " contains characters that are not allowed.");
            }
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new BadRequestException("The " + what + " is not a valid web address.");
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            throw new BadRequestException("The " + what + " must start with https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getHost().indexOf('.') < 1) {
            throw new BadRequestException("The " + what + " is not a valid web address.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("The " + what + " must not contain a username or password.");
        }
        return java.util.Optional.of(uri);
    }
}
