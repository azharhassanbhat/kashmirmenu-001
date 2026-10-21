package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.FeedbackRules.Feedback;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** What a guest may say about a meal, and when. */
class FeedbackRulesTest {

    private static final LocalDateTime PLACED = LocalDateTime.of(2026, 10, 15, 13, 0);

    private static void refused(Runnable r, String fragment) {
        BadRequestException e = assertThrows(BadRequestException.class, r::run);
        assertTrue(e.getMessage().toLowerCase().contains(fragment.toLowerCase()), e.getMessage());
    }

    // ------------------------------------------------------------------ the rating

    @Test
    void ratingsOneToFiveAreAccepted() {
        for (int r = 1; r <= 5; r++) {
            assertEquals(r, FeedbackRules.validate(r, null).rating());
        }
    }

    @Test
    void anythingElseIsRefused() {
        for (Integer bad : new Integer[]{null, 0, 6, -1, 100, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            refused(() -> FeedbackRules.validate(bad, "ok"), "rating from 1 to 5");
        }
    }

    @Test
    void aWholeNumberSentAsADecimalIsStillThatNumber() {
        assertEquals(4, FeedbackRules.wholeRating(new java.math.BigDecimal("4")));
        assertEquals(4, FeedbackRules.wholeRating(new java.math.BigDecimal("4.0")));
        assertEquals(5, FeedbackRules.wholeRating(new java.math.BigDecimal("5.000")));
        assertEquals(3, FeedbackRules.wholeRating(new java.math.BigDecimal("3E+0")));
        assertNull(FeedbackRules.wholeRating(null), "left to validate() to report as missing");
    }

    @Test
    void aFractionIsRefusedNotQuietlyRoundedDown() {
        for (String bad : new String[]{"2.5", "4.1", "4.9999", "0.5", "1e-1"}) {
            refused(() -> FeedbackRules.wholeRating(new java.math.BigDecimal(bad)), "rating from 1 to 5");
        }
    }

    @Test
    void aNumberTooBigForAnIntegerIsRefusedNotWrappedAround() {
        // 2^32 + 3 would wrap to 3 if it were cast; it must never become a valid-looking rating
        refused(() -> FeedbackRules.wholeRating(new java.math.BigDecimal("4294967299")), "rating from 1 to 5");
        refused(() -> FeedbackRules.wholeRating(new java.math.BigDecimal("1e100")), "rating from 1 to 5");
    }

    // ------------------------------------------------------------------ the comment

    @Test
    void aCommentIsOptional() {
        assertNull(FeedbackRules.validate(5, null).comment());
        assertNull(FeedbackRules.validate(5, "").comment());
        assertNull(FeedbackRules.validate(5, "   \n\t ").comment());
    }

    @Test
    void aCommentIsKeptCleaned() {
        Feedback f = FeedbackRules.validate(4, "  Lovely   rogan\n josh\u0000 ");
        assertEquals("Lovely rogan josh", f.comment());
    }

    @Test
    void aCommentIsCappedAtFiveHundredCharacters() {
        assertEquals(500, FeedbackRules.validate(3, "x".repeat(500)).comment().length());
        refused(() -> FeedbackRules.validate(3, "x".repeat(501)), "under 500");
    }

    @Test
    void aCommentInAnyScriptIsKept() {
        String urdu = "\u0628\u06c1\u062a \u0627\u0686\u06be\u0627";
        assertEquals(urdu, FeedbackRules.validate(5, urdu).comment());
    }

    @Test
    void theCapCountsCharactersNotBytes() {
        String emoji = "\uD83D\uDE00".repeat(500);          // 500 characters, 1000 UTF-16 units
        assertDoesNotThrow(() -> FeedbackRules.validate(5, emoji));
        refused(() -> FeedbackRules.validate(5, emoji + "\uD83D\uDE00"), "under 500");
    }

    @Test
    void aBadRatingIsReportedBeforeAnythingElse() {
        refused(() -> FeedbackRules.validate(0, "x".repeat(900)), "rating");
    }

    // ------------------------------------------------------------------ when

    @Test
    void aServedOrderMayBeRatedWithinTheWindow() {
        assertDoesNotThrow(() -> FeedbackRules.requireOpen(OrderStatus.SERVED, PLACED, PLACED.plusHours(2)));
        assertDoesNotThrow(() -> FeedbackRules.requireOpen(OrderStatus.SERVED, PLACED, PLACED.plusDays(3)));   // exactly on the limit
    }

    @Test
    void anOldVisitCannotBeRated() {
        refused(() -> FeedbackRules.requireOpen(OrderStatus.SERVED, PLACED, PLACED.plusDays(3).plusSeconds(1)), "within 3 days");
        refused(() -> FeedbackRules.requireOpen(OrderStatus.SERVED, PLACED, PLACED.plusDays(90)), "within 3 days");
    }

    @Test
    void anOrderNotYetServedCannotBeRated() {
        for (OrderStatus s : new OrderStatus[]{OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY}) {
            refused(() -> FeedbackRules.requireOpen(s, PLACED, PLACED.plusMinutes(5)), "once your order has been served");
        }
    }

    @Test
    void aCancelledOrderSaysSoInsteadOfSuggestingToWait() {
        refused(() -> FeedbackRules.requireOpen(OrderStatus.CANCELLED, PLACED, PLACED.plusMinutes(5)), "cancelled");
    }

    @Test
    void missingTimesAreNotFatal() {
        assertDoesNotThrow(() -> FeedbackRules.requireOpen(OrderStatus.SERVED, null, null));
    }
}
