package com.kashmirmenu.app.coupon;

import java.util.Locale;

/**
 * The one place a coupon code is normalised.
 *
 * <p>Codes are stored upper-cased and trimmed, so every lookup must normalise its input the same way
 * or the equality match silently fails. Having a single function means the write path and all read
 * paths cannot drift: the previous approach - {@code upper(c.code) = upper(:code)} in the query -
 * tolerated any input but could not use the index on {@code code}.
 *
 * <p>{@link Locale#ROOT} rather than the default locale: in a Turkish locale {@code "i".toUpperCase()}
 * yields a dotted capital I, so a code containing an i would normalise differently depending on where
 * the server runs.
 */
public final class CouponCode {

    private CouponCode() {
    }

    /** @return the normalised code, or an empty string for null/blank input */
    public static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
