package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.landing.AnonymousFeedbackRules.FeedbackCategory;
import com.kashmirmenu.app.service.landing.AnonymousFeedbackRules.Clean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnonymousFeedbackRulesTest {

    private static void refused(Runnable r, String fragment) {
        BadRequestException e = assertThrows(BadRequestException.class, r::run);
        assertTrue(e.getMessage().toLowerCase().contains(fragment.toLowerCase()), e.getMessage());
    }

    @Test
    void aRatingAloneIsEnough() {
        Clean c = AnonymousFeedbackRules.validate(4, null, null, false, null);
        assertEquals(4, c.rating());
        assertEquals(FeedbackCategory.OTHER, c.category());
        assertNull(c.comment());
        assertNull(c.contact());
    }

    @Test
    void ratingsOutsideOneToFiveAreRefused() {
        for (Integer bad : new Integer[]{null, 0, 6, -1, Integer.MAX_VALUE}) {
            refused(() -> AnonymousFeedbackRules.validate(bad, "FOOD", "x", false, null), "rating from 1 to 5");
        }
    }

    @Test
    void everyDocumentedFeedbackCategoryIsAccepted() {
        for (String name : new String[]{"food", "SERVICE", " Cleanliness ", "ambience", "other"}) {
            assertNotNull(AnonymousFeedbackRules.validate(3, name, null, false, null).category(), name);
        }
        assertEquals(FeedbackCategory.FOOD, AnonymousFeedbackRules.validate(3, "food", null, false, null).category());
    }

    @Test
    void anUnknownFeedbackCategoryIsRefusedNotSilentlyChanged() {
        refused(() -> AnonymousFeedbackRules.validate(3, "parking", null, false, null), "food, service");
    }

    @Test
    void commentsAreCleanedAndBounded() {
        assertEquals("Great kahwa", AnonymousFeedbackRules.validate(5, "FOOD", "  Great\n\t kahwa \u0000", false, null).comment());
        assertNull(AnonymousFeedbackRules.validate(5, "FOOD", "   \n ", false, null).comment());
        assertNotNull(AnonymousFeedbackRules.validate(5, "FOOD", "a".repeat(1000), false, null).comment());
        refused(() -> AnonymousFeedbackRules.validate(5, "FOOD", "a".repeat(1001), false, null), "under 1000");
    }

    @Test
    void emojiCountAsOneCharacterEach() {
        String emoji = "\uD83D\uDE00".repeat(1000); // 1000 characters, 2000 UTF-16 units
        assertNotNull(AnonymousFeedbackRules.validate(5, "FOOD", emoji, false, null).comment());
    }

    @Test
    void contactDetailsAreDroppedUnlessTheGuestAskedToBeContacted() {
        Clean anonymous = AnonymousFeedbackRules.validate(2, "SERVICE", "slow", false, "me@example.com");
        assertNull(anonymous.contact(), "an untouched anonymous form must not keep an address");
    }

    @Test
    void contactDetailsAreKeptWhenTheGuestOptsIn() {
        Clean identified = AnonymousFeedbackRules.validate(2, "SERVICE", "slow", true, " me@example.com ");
        assertEquals("me@example.com", identified.contact());
    }

    @Test
    void optingInWithoutContactDetailsIsRefused() {
        refused(() -> AnonymousFeedbackRules.validate(2, "SERVICE", "slow", true, "  "), "reach you");
        refused(() -> AnonymousFeedbackRules.validate(2, "SERVICE", "slow", true, null), "reach you");
        refused(() -> AnonymousFeedbackRules.validate(2, "SERVICE", "slow", true, "x".repeat(101)), "under 100");
    }
}
