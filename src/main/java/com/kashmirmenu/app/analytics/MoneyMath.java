package com.kashmirmenu.app.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact-decimal helpers. Nothing in the analytics path touches float or double. */
public final class MoneyMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MoneyMath() {
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    public static BigDecimal money(BigDecimal v) {
        return v == null ? zero() : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** part / whole as a percentage with one decimal; 0.0 when whole is zero. */
    public static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Percentage change from previous to current; null when previous is zero (no meaningful base). */
    public static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).multiply(HUNDRED).divide(previous.abs(), 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal average(BigDecimal total, long count) {
        if (count <= 0) {
            return zero();
        }
        return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    public static AnalyticsOverview.Delta delta(BigDecimal current, BigDecimal previous) {
        return new AnalyticsOverview.Delta(
                money(current.subtract(previous)), percentChange(current, previous));
    }

    /**
     * Formats an amount for text such as insights. INR uses Indian digit grouping (₹1,85,450) and drops
     * the paise when the amount is whole; other currencies get a code prefix and western grouping.
     */
    public static String format(BigDecimal value, String currency) {
        BigDecimal v = money(value);
        boolean negative = v.signum() < 0;
        v = v.abs();
        String[] parts = v.toPlainString().split("\\.");
        String whole = parts[0];
        String fraction = parts.length > 1 ? parts[1] : "00";
        boolean inr = currency == null || "INR".equalsIgnoreCase(currency);

        String grouped = inr ? groupIndian(whole) : groupWestern(whole);
        String number = "00".equals(fraction) ? grouped : grouped + "." + fraction;
        String prefix = inr ? "₹" : currency.toUpperCase() + " ";
        return (negative ? "-" : "") + prefix + number;
    }

    private static String groupIndian(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) {
                sb.append(',');
            }
            sb.append(rest.charAt(i));
        }
        return sb + "," + last3;
    }

    private static String groupWestern(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(',');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }
}
