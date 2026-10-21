package com.kashmirmenu.app.service.qr;

import java.util.Comparator;
import java.util.Locale;

/**
 * The small rules behind table QR cards. Pure (no ZXing, no Spring), so they are testable.
 */
public final class QrRules {

    public static final int DEFAULT_SIZE = 300;
    public static final int MIN_SIZE = 200;
    public static final int MAX_SIZE = 1200;

    private QrRules() {
    }

    /**
     * The address a table's QR code opens: the site's base address plus /menu/ and the table's identifier.
     * Trailing slashes on the base are removed, so a base address configured as "https://site.com/" cannot
     * print "https://site.com//menu/..." on every card. Printed codes cannot be recalled, so this must be right.
     */
    public static String tableUrl(String baseUrl, String qrIdentifier) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("The public base address is not configured, so a QR code cannot be made.");
        }
        if (qrIdentifier == null || qrIdentifier.isBlank()) {
            throw new IllegalArgumentException("A table has no QR identifier.");
        }
        return baseUrl.strip().replaceAll("/+$", "") + "/menu/" + qrIdentifier.strip();
    }

    /**
     * The size in pixels of the QR image to draw, from a request parameter. Larger means sharper when printed
     * big (a 300 pixel image printed 5 cm wide is only 150 dots per inch). Missing, unreadable and out-of-range
     * values never fail the request: they fall back to the default or are held to the limits.
     */
    public static int size(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_SIZE;
        }
        try {
            long n = Long.parseLong(requested.strip());
            return (int) Math.max(MIN_SIZE, Math.min(MAX_SIZE, n));
        } catch (NumberFormatException e) {
            return DEFAULT_SIZE;
        }
    }

    /**
     * Orders table names the way a person expects: "Table 2" before "Table 10", ignoring capitals. A plain
     * alphabetical sort puts "Table 10" before "Table 2", which scrambles a printed sheet of cards.
     */
    public static final Comparator<String> NATURAL_ORDER = (a, b) -> {
        String x = a == null ? "" : a.toLowerCase(Locale.ROOT);
        String y = b == null ? "" : b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char c = x.charAt(i), d = y.charAt(j);
            if (Character.isDigit(c) && Character.isDigit(d)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) {
                    i++;
                }
                while (j < y.length() && Character.isDigit(y.charAt(j))) {
                    j++;
                }
                String nx = x.substring(si, i).replaceFirst("^0+(?=.)", "");
                String ny = y.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (nx.length() != ny.length()) {
                    return Integer.compare(nx.length(), ny.length());       // more digits, a bigger number
                }
                int cmp = nx.compareTo(ny);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                if (c != d) {
                    return Character.compare(c, d);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(x.length() - i, y.length() - j);
    };
}
