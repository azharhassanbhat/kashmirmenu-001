package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;

/**
 * The rewards arithmetic and eligibility rules. Pure: the service applies them inside a transaction, and the
 * database's own constraints (one claim per order, balance never below zero) are the last line of defence.
 *
 * <p>Points come only from a served order, worked out on the server from the order's own total. Nothing a client
 * sends can change how many points an order is worth, and opening the landing page or tapping the review button
 * is worth nothing.
 */
public final class RewardRules {

    public static final BigDecimal DEFAULT_SPEND_PER_POINT = new BigDecimal("10.00");
    public static final int DEFAULT_REDEEM_POINTS = 100;
    public static final int MAX_REDEEM_POINTS = 100_000;
    public static final BigDecimal MAX_SPEND_PER_POINT = new BigDecimal("100000.00");
    /** The column holds two decimals, so anything that would round to zero is refused rather than stored as 0.00. */
    public static final BigDecimal MIN_SPEND_PER_POINT = new BigDecimal("0.01");

    /** No 0/O or 1/I/L, so a code read aloud or typed from a phone screen is not mistaken. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RewardRules() {
    }

    /** Whole points for an order total: one point per {@code spendPerPoint} spent, rounded down, never negative. */
    public static int pointsFor(BigDecimal orderTotal, BigDecimal spendPerPoint) {
        if (orderTotal == null || spendPerPoint == null
                || orderTotal.signum() <= 0 || spendPerPoint.signum() <= 0) {
            return 0;
        }
        BigDecimal points = orderTotal.divide(spendPerPoint, 0, RoundingMode.DOWN);
        return points.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : points.intValue();
    }

    /** Refuses a claim on an order that has not been served (or was cancelled). */
    public static void requireClaimable(OrderStatus status) {
        if (status == OrderStatus.CANCELLED) {
            throw new BadRequestException("This order was cancelled, so it earns no points.");
        }
        if (status != OrderStatus.SERVED) {
            throw new BadRequestException("Points can be added once your order has been served.");
        }
    }

    /**
     * Refuses a claim until the bill is settled. {@code paidInFull} is the sum of the order's payments that staff
     * confirmed as received and that have not been refunded; a served order nobody paid for earns nothing, and a
     * refunded one earns nothing either (the safe side: nobody keeps points for money that went back).
     */
    public static void requirePaid(BigDecimal orderTotal, BigDecimal paidInFull) {
        BigDecimal total = orderTotal == null ? BigDecimal.ZERO : orderTotal;
        BigDecimal paid = paidInFull == null ? BigDecimal.ZERO : paidInFull;
        if (paid.compareTo(total) < 0 || paid.signum() <= 0) {
            throw new BadRequestException(
                    "Points are added once your bill has been paid and the restaurant has confirmed it.");
        }
    }

    public static boolean canRedeem(int balance, int cost) {
        return cost > 0 && balance >= cost;
    }

    /** How many more points until the reward (0 when already reachable). */
    public static int pointsToGo(int balance, int cost) {
        return Math.max(0, cost - Math.max(0, balance));
    }

    /** What is stored: two decimals, rounded half up (the column's own precision). Null stays null. */
    public static BigDecimal normaliseSpend(BigDecimal spendPerPoint) {
        return spendPerPoint == null ? null : spendPerPoint.setScale(2, RoundingMode.HALF_UP);
    }

    /** Owner settings: spend-per-point must be positive and sane, the reward cost a positive whole number. */
    public static void validateSettings(BigDecimal spendPerPoint, Integer redeemPoints) {
        if (spendPerPoint == null || spendPerPoint.compareTo(MIN_SPEND_PER_POINT) < 0
                || spendPerPoint.compareTo(MAX_SPEND_PER_POINT) > 0) {
            throw new BadRequestException("Enter how much a guest spends to earn one point (at least 0.01).");
        }
        if (redeemPoints == null || redeemPoints < 1 || redeemPoints > MAX_REDEEM_POINTS) {
            throw new BadRequestException("Enter how many points one reward costs (1 to " + MAX_REDEEM_POINTS + ").");
        }
    }

    /** A new random member code. Shown to the guest and read to staff; it identifies, it does not authenticate. */
    public static String newMemberCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Normalises what staff typed: trims, upper-cases, drops spaces and dashes. Null if it cannot be a code. */
    public static String cleanCode(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (c.length() != CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < c.length(); i++) {
            if (CODE_ALPHABET.indexOf(c.charAt(i)) < 0) {
                return null;
            }
        }
        return c;
    }

    /** A guest's secret: long enough that it cannot be guessed. Stored only as a hash. */
    public static boolean plausibleSecret(String secret) {
        return secret != null && secret.length() >= 24 && secret.length() <= 200
                && secret.chars().allMatch(ch -> ch > 32 && ch < 127);
    }
}
