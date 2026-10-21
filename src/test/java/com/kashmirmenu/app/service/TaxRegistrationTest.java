package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The tax registration number printed on receipts. */
class TaxRegistrationTest {

    @Test
    void blankMeansNone() {
        assertNull(TaxRegistration.clean(null));
        assertNull(TaxRegistration.clean(""));
        assertNull(TaxRegistration.clean("   \t "));
    }

    @Test
    void aGstinIsKeptTidyAndUpperCase() {
        assertEquals("29ABCDE1234F1Z5", TaxRegistration.clean("29abcde1234f1z5"));
        assertEquals("29ABCDE1234F1Z5", TaxRegistration.clean("  29 ABCDE 1234 F1Z5 "));
    }

    @Test
    void otherCountriesNumbersAreAcceptedToo() {
        assertEquals("GB123456789", TaxRegistration.clean("gb 123 456 789"));
        assertEquals("DE-123/45", TaxRegistration.clean("de-123/45"));
        assertEquals("ABC12", TaxRegistration.clean("abc12"));
    }

    @Test
    void numbersCopiedFromAWebPageOrWhatsAppAreTidiedToo() {
        // non-breaking space, em space, narrow no-break space, ideographic space, byte-order mark
        assertEquals("29ABCDE1234F1Z5", TaxRegistration.clean("29ABCDE\u00a01234\u200aF1Z5"));
        assertEquals("ABC12", TaxRegistration.clean("ABC\u200312"));
        assertEquals("ABC12", TaxRegistration.clean("ABC\u202f12"));
        assertEquals("ABCDE", TaxRegistration.clean("AB\u3000CDE"));
        assertEquals("ABC12", TaxRegistration.clean("\uFEFFABC12"));
    }

    @Test
    void aZeroWidthSpaceIsNotATidyableSpaceSoItIsRefused() {
        assertThrows(BadRequestException.class, () -> TaxRegistration.clean("ABC\u200b12"));
    }

    @Test
    void lengthIsFiveToThirty() {
        assertThrows(BadRequestException.class, () -> TaxRegistration.clean("ABCD"));
        assertEquals("ABCDE", TaxRegistration.clean("ABCDE"));
        assertEquals(30, TaxRegistration.clean("A".repeat(30)).length());
        assertThrows(BadRequestException.class, () -> TaxRegistration.clean("A".repeat(31)));
    }

    @Test
    void anythingThatCouldBreakAPrintoutIsRefused() {
        for (String bad : new String[]{"<script>1</script>", "ABC12;DROP", "ABC12\u0000", "ABC 12 & CO", "GSTIN: 29ABCDE1234F1Z5",
                "29ABCDE1234F1Z5!", "\u0917\u0941\u0936\u094D\u0924\u093E", "ABC12'--", "ABC12\"", "=1+1+1"}) {
            assertThrows(BadRequestException.class, () -> TaxRegistration.clean(bad), bad);
        }
    }

    @Test
    void theMessageGivesAnExample() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> TaxRegistration.clean("no"));
        assertTrue(e.getMessage().contains("29ABCDE1234F1Z5"));
        assertTrue(e.getMessage().contains("5 to 30"));
    }
}
