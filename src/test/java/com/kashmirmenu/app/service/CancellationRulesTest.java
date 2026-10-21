package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.CancelReason;
import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What may accompany cancelling an order. */
class CancellationRulesTest {

    @Test
    void aReasonIsOptionalSoOlderCallersStillWork() {
        var c = CancellationRules.validate(null, null);
        assertNull(c.reason());
        assertNull(c.note());
        assertNull(CancellationRules.validate("", "   ").reason());
    }

    @Test
    void aListedReasonIsAccepted() {
        assertEquals(CancelReason.OUT_OF_STOCK, CancellationRules.validate("OUT_OF_STOCK", null).reason());
        assertEquals(CancelReason.GUEST_REQUEST, CancellationRules.validate("guest_request", null).reason());
        assertEquals(CancelReason.DUPLICATE_ORDER, CancellationRules.validate("  duplicate_order ", null).reason());
    }

    @Test
    void anUnknownReasonIsRefused() {
        assertThrows(BadRequestException.class, () -> CancellationRules.validate("BECAUSE", null));
        assertThrows(BadRequestException.class, () -> CancellationRules.validate("out of stock", null));
    }

    @Test
    void otherNeedsANoteSayingWhat() {
        assertThrows(BadRequestException.class, () -> CancellationRules.validate("OTHER", null));
        assertThrows(BadRequestException.class, () -> CancellationRules.validate("OTHER", "   \n "));
        var c = CancellationRules.validate("OTHER", "Power cut in the kitchen");
        assertEquals(CancelReason.OTHER, c.reason());
        assertEquals("Power cut in the kitchen", c.note());
    }

    @Test
    void aNoteIsOptionalForTheOtherReasons() {
        assertNull(CancellationRules.validate("OUT_OF_STOCK", null).note());
        assertEquals("No more lamb", CancellationRules.validate("OUT_OF_STOCK", "No more lamb").note());
    }

    @Test
    void notesAreCleanedAndCapped() {
        assertEquals("a b", CancellationRules.validate("OUT_OF_STOCK", "  a \n\t b ").note());
        assertEquals(200, CancellationRules.validate("OUT_OF_STOCK", "x".repeat(200)).note().length());
        assertThrows(BadRequestException.class, () -> CancellationRules.validate("OUT_OF_STOCK", "x".repeat(201)));
    }

    @Test
    void aNoteWithoutAReasonIsKeptNotDropped() {
        assertEquals("Guest left", CancellationRules.validate(null, "Guest left").note());
    }

    @Test
    void everyReasonHasALabelAGuestCanRead() {
        for (CancelReason r : CancelReason.values()) {
            assertFalse(r.label().isBlank(), r.name());
        }
    }
}
