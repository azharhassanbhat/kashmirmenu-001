package com.kashmirmenu.app.service.qr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The small rules behind table QR cards. */
class QrRulesTest {

    // ------------------------------------------------------------------ the address

    @Test
    void theAddressIsTheBasePlusMenuPlusTheIdentifier() {
        assertEquals("https://menu.example.com/menu/abc123", QrRules.tableUrl("https://menu.example.com", "abc123"));
    }

    @Test
    void aTrailingSlashOnTheBaseNeverDoublesUp() {
        assertEquals("https://menu.example.com/menu/abc123", QrRules.tableUrl("https://menu.example.com/", "abc123"));
        assertEquals("https://menu.example.com/menu/abc123", QrRules.tableUrl("https://menu.example.com///", "abc123"));
        // checked on a host that does not itself contain "//menu"
        assertFalse(QrRules.tableUrl("https://site.example.com/", "x").contains("//menu"));
        assertEquals("https://site.example.com/menu/x", QrRules.tableUrl("https://site.example.com/", "x"));
    }

    @Test
    void aPathInTheBaseIsKept() {
        assertEquals("https://example.com/eat/menu/abc", QrRules.tableUrl("https://example.com/eat/", "abc"));
    }

    @Test
    void spacesAroundTheInputsAreIgnored() {
        assertEquals("https://example.com/menu/abc", QrRules.tableUrl("  https://example.com/  ", " abc "));
    }

    @Test
    void aMissingBaseOrIdentifierIsRefusedNotPrintedAsGarbage() {
        assertThrows(IllegalStateException.class, () -> QrRules.tableUrl(null, "abc"));
        assertThrows(IllegalStateException.class, () -> QrRules.tableUrl("  ", "abc"));
        assertThrows(IllegalArgumentException.class, () -> QrRules.tableUrl("https://example.com", null));
        assertThrows(IllegalArgumentException.class, () -> QrRules.tableUrl("https://example.com", " "));
    }

    // ------------------------------------------------------------------ the size

    @Test
    void noSizeMeansTheDefault() {
        assertEquals(QrRules.DEFAULT_SIZE, QrRules.size(null));
        assertEquals(QrRules.DEFAULT_SIZE, QrRules.size(""));
        assertEquals(QrRules.DEFAULT_SIZE, QrRules.size("   "));
    }

    @Test
    void aSensibleSizeIsUsedAsAsked() {
        assertEquals(800, QrRules.size("800"));
        assertEquals(800, QrRules.size(" 800 "));
        assertEquals(QrRules.MIN_SIZE, QrRules.size("200"));
        assertEquals(QrRules.MAX_SIZE, QrRules.size("1200"));
    }

    @Test
    void anAbsurdSizeIsHeldToTheLimitsAndCannotExhaustMemory() {
        assertEquals(QrRules.MAX_SIZE, QrRules.size("99999"));
        assertEquals(QrRules.MAX_SIZE, QrRules.size("2147483647"));
        assertEquals(QrRules.DEFAULT_SIZE, QrRules.size("99999999999999999999"));   // too big even for a long: unreadable
        assertEquals(QrRules.MIN_SIZE, QrRules.size("1"));
        assertEquals(QrRules.MIN_SIZE, QrRules.size("0"));
        assertEquals(QrRules.MIN_SIZE, QrRules.size("-500"));
    }

    @Test
    void unreadableValuesFallBackToTheDefaultInsteadOfFailing() {
        for (String junk : new String[]{"abc", "8e2", "800px", "8.5", "0x20", "800 800"}) {
            assertEquals(QrRules.DEFAULT_SIZE, QrRules.size(junk), junk);
        }
    }

    // ------------------------------------------------------------------ the order

    private static List<String> sorted(String... names) {
        List<String> list = new ArrayList<>(Arrays.asList(names));
        list.sort(QrRules.NATURAL_ORDER);
        return list;
    }

    @Test
    void numbersAreOrderedAsNumbersNotAsText() {
        assertEquals(List.of("Table 1", "Table 2", "Table 10", "Table 11", "Table 100"),
                sorted("Table 100", "Table 10", "Table 2", "Table 11", "Table 1"));
    }

    @Test
    void capitalsDoNotMatter() {
        assertEquals(List.of("table 1", "TABLE 2", "Table 3"), sorted("Table 3", "table 1", "TABLE 2"));
    }

    @Test
    void zonesAreGroupedAndTheNumbersWithinThemOrdered() {
        assertEquals(List.of("Garden 1", "Garden 2", "Garden 10", "Terrace 1", "Terrace 3"),
                sorted("Terrace 3", "Garden 10", "Garden 2", "Terrace 1", "Garden 1"));
    }

    @Test
    void leadingZerosAreIgnoredForTheValue() {
        assertEquals(List.of("T1", "T02", "T10"), sorted("T10", "T02", "T1"));
    }

    @Test
    void aNameThatIsAPrefixComesFirst() {
        assertEquals(List.of("Table", "Table 1", "Table 1A"), sorted("Table 1A", "Table", "Table 1"));
    }

    @Test
    void nullsAndEmptyNamesDoNotCrashTheSort() {
        List<String> list = new ArrayList<>(Arrays.asList("B", null, "", "A"));
        assertDoesNotThrow(() -> list.sort(QrRules.NATURAL_ORDER));
    }

    @Test
    void theOrderIsConsistentSoSortingNeverThrows() {
        // an inconsistent comparator makes List.sort throw "Comparison method violates its general contract"
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            names.add("Table " + (i * 37 % 120));
            names.add("T" + (i % 17) + "x");
            names.add("garden " + (i % 9));
        }
        assertDoesNotThrow(() -> names.sort(QrRules.NATURAL_ORDER));
        for (int i = 1; i < names.size(); i++) {
            assertTrue(QrRules.NATURAL_ORDER.compare(names.get(i - 1), names.get(i)) <= 0);
        }
    }

    @Test
    void identicalNamesCompareEqual() {
        assertEquals(0, QrRules.NATURAL_ORDER.compare("Table 7", "Table 7"));
        assertEquals(0, QrRules.NATURAL_ORDER.compare("Table 7", "TABLE 7"));
    }
}
