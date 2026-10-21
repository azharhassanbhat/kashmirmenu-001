package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RewardRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void pointsAreWholeAndRoundedDown() {
        assertEquals(12, RewardRules.pointsFor(d("125.00"), d("10")));
        assertEquals(12, RewardRules.pointsFor(d("129.99"), d("10")));
        assertEquals(0, RewardRules.pointsFor(d("9.99"), d("10")));
        assertEquals(1, RewardRules.pointsFor(d("10.00"), d("10")));
        assertEquals(250, RewardRules.pointsFor(d("1250"), d("5")));
    }

    @Test
    void nothingBadEverEarnsPoints() {
        assertEquals(0, RewardRules.pointsFor(null, d("10")));
        assertEquals(0, RewardRules.pointsFor(d("100"), null));
        assertEquals(0, RewardRules.pointsFor(d("-50"), d("10")));
        assertEquals(0, RewardRules.pointsFor(d("0"), d("10")));
        assertEquals(0, RewardRules.pointsFor(d("100"), d("0")));
        assertEquals(0, RewardRules.pointsFor(d("100"), d("-10")));
    }

    @Test
    void anAbsurdTotalIsCappedNotWrappedAround() {
        assertEquals(Integer.MAX_VALUE, RewardRules.pointsFor(d("99999999999999"), d("0.01")));
    }

    @Test
    void onlyAServedOrderCanBeClaimed() {
        RewardRules.requireClaimable(OrderStatus.SERVED);
        for (OrderStatus s : new OrderStatus[]{OrderStatus.PENDING, OrderStatus.CONFIRMED,
                OrderStatus.PREPARING, OrderStatus.READY}) {
            BadRequestException e = assertThrows(BadRequestException.class, () -> RewardRules.requireClaimable(s));
            assertTrue(e.getMessage().contains("served"), e.getMessage());
        }
        BadRequestException cancelled =
                assertThrows(BadRequestException.class, () -> RewardRules.requireClaimable(OrderStatus.CANCELLED));
        assertTrue(cancelled.getMessage().contains("cancelled"));
    }

    @Test
    void aBillMustBePaidInFullBeforeItEarnsPoints() {
        RewardRules.requirePaid(d("500.00"), d("500.00"));
        RewardRules.requirePaid(d("500.00"), d("520.00")); // a tip on top is fine
        for (BigDecimal paid : new BigDecimal[]{null, d("0"), d("499.99"), d("-5")}) {
            BadRequestException e =
                    assertThrows(BadRequestException.class, () -> RewardRules.requirePaid(d("500.00"), paid));
            assertTrue(e.getMessage().contains("paid"), e.getMessage());
        }
    }

    @Test
    void aZeroValueOrderCanNeverCountAsPaid() {
        assertThrows(BadRequestException.class, () -> RewardRules.requirePaid(d("0"), d("0")));
        assertThrows(BadRequestException.class, () -> RewardRules.requirePaid(null, null));
    }

    @Test
    void redeemingNeedsEnoughPoints() {
        assertTrue(RewardRules.canRedeem(100, 100));
        assertTrue(RewardRules.canRedeem(250, 100));
        assertFalse(RewardRules.canRedeem(99, 100));
        assertFalse(RewardRules.canRedeem(0, 100));
        assertFalse(RewardRules.canRedeem(100, 0));
        assertFalse(RewardRules.canRedeem(100, -5));
    }

    @Test
    void pointsToGoNeverGoesNegative() {
        assertEquals(40, RewardRules.pointsToGo(60, 100));
        assertEquals(0, RewardRules.pointsToGo(100, 100));
        assertEquals(0, RewardRules.pointsToGo(500, 100));
        assertEquals(100, RewardRules.pointsToGo(-3, 100));
    }

    @Test
    void ownerSettingsAreChecked() {
        RewardRules.validateSettings(d("10"), 100);
        RewardRules.validateSettings(d("0.01"), 100);
        for (BigDecimal bad : new BigDecimal[]{null, d("0"), d("-1"), d("100001"), d("0.009")}) {
            assertThrows(BadRequestException.class, () -> RewardRules.validateSettings(bad, 100));
        }
        for (Integer bad : new Integer[]{null, 0, -1, RewardRules.MAX_REDEEM_POINTS + 1}) {
            assertThrows(BadRequestException.class, () -> RewardRules.validateSettings(d("10"), bad));
        }
    }

    @Test
    void spendIsRoundedToTheColumnsTwoDecimalsBeforeItIsChecked() {
        assertEquals(d("10.01"), RewardRules.normaliseSpend(d("10.005")));
        assertEquals(d("10.00"), RewardRules.normaliseSpend(d("10")));
        assertNull(RewardRules.normaliseSpend(null));
        // 0.004 would be stored as 0.00 and trip the database's own check, so it is refused up front
        assertThrows(BadRequestException.class, () -> RewardRules.validateSettings(RewardRules.normaliseSpend(d("0.004")), 100));
        RewardRules.validateSettings(RewardRules.normaliseSpend(d("0.005")), 100); // rounds up to 0.01
    }

    @Test
    void memberCodesAreShortUnambiguousAndDifferent() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = RewardRules.newMemberCode();
            assertEquals(RewardRules.CODE_LENGTH, code.length());
            assertTrue(code.matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}"), code);
            assertEquals(code, RewardRules.cleanCode(code));
            seen.add(code);
        }
        assertTrue(seen.size() > 495, "codes should essentially never repeat");
    }

    @Test
    void staffTypedCodesAreTidiedAndBadOnesRejected() {
        assertEquals("ABCD2345", RewardRules.cleanCode(" abcd-2345 "));
        assertEquals("ABCD2345", RewardRules.cleanCode("ab cd 23 45"));
        assertNull(RewardRules.cleanCode(null));
        assertNull(RewardRules.cleanCode("ABC"));
        assertNull(RewardRules.cleanCode("ABCDEFGHI"));
        assertNull(RewardRules.cleanCode("ABCD1234")); // contains the ambiguous 1
        assertNull(RewardRules.cleanCode("ABCD0OIL"));
    }

    @Test
    void aGuestSecretMustBeLongAndPlain() {
        assertTrue(RewardRules.plausibleSecret("a".repeat(24)));
        assertTrue(RewardRules.plausibleSecret("Zx9-" + "k".repeat(40)));
        assertFalse(RewardRules.plausibleSecret(null));
        assertFalse(RewardRules.plausibleSecret("short"));
        assertFalse(RewardRules.plausibleSecret("a".repeat(201)));
        assertFalse(RewardRules.plausibleSecret("a".repeat(23) + " "));
        assertFalse(RewardRules.plausibleSecret("a".repeat(23) + "\n"));
    }
}
