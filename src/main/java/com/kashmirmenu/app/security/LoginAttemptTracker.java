package com.kashmirmenu.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts failed sign-ins per ACCOUNT, not per address.
 *
 * <p>The per-IP limit on the auth endpoints stops one machine guessing quickly, but a guesser who spreads
 * attempts over many addresses is never slowed by it. After {@code limit} failures for one email inside the
 * window, further attempts for that email are refused until the window rolls over, whichever address they come
 * from. A successful sign-in clears the count (it needs the password, so it proves the owner is present).
 *
 * <p>Trade-off, stated plainly: someone who knows an email can keep that one account in the refused state, with
 * a few requests per window. The ceiling is high enough that a person mistyping a password never meets it, and
 * the cost of the alternative (unlimited guessing against a restaurant owner's account) is higher.
 *
 * <p>The key is a hash of the lower-cased email, so the map never holds addresses, and the map is capped. State
 * is per instance, like the other limiters; an edge limit is still the right place for the rest.
 */
public final class LoginAttemptTracker {

    static final int MAX_KEYS = 50_000;

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public LoginAttemptTracker(int limit, int windowSeconds) {
        this(limit, windowSeconds, System::currentTimeMillis);
    }

    LoginAttemptTracker(int limit, int windowSeconds, LongSupplier clockMillis) {
        this.limit = limit;
        this.windowMillis = windowSeconds * 1000L;
        this.clockMillis = clockMillis;
    }

    /** The map key for an email: a hash, trimmed and lower-cased so case tricks do not give fresh attempts. */
    public static String keyFor(String email) {
        String normalised = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when this account has used up its failures for the current window. Does not count. */
    public boolean isLocked(String key) {
        Entry e = entries.get(key);
        return e != null && e.isLocked(limit, windowMillis, clockMillis.getAsLong());
    }

    public void recordFailure(String key) {
        if (entries.size() > MAX_KEYS) {
            entries.clear();
        }
        entries.computeIfAbsent(key, k -> new Entry()).add(windowMillis, clockMillis.getAsLong());
    }

    public void reset(String key) {
        entries.remove(key);
    }

    int size() {
        return entries.size();
    }

    private static final class Entry {
        private long start = -1;
        private int failures;

        synchronized void add(long windowMillis, long now) {
            if (start < 0 || now > start + windowMillis) {
                start = now;
                failures = 0;
            }
            failures++;
        }

        synchronized boolean isLocked(int limit, long windowMillis, long now) {
            if (start < 0 || now > start + windowMillis) {
                return false;
            }
            return failures >= limit;
        }
    }
}
