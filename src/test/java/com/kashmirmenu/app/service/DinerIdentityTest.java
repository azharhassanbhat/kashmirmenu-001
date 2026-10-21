package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DinerIdentityTest {

    @Test
    void aNameIsTrimmedAndInnerWhitespaceCollapsed() {
        assertEquals("Rahul Sharma", DinerIdentity.normalizeName("  Rahul \t  Sharma \n"));
    }

    @Test
    void namesInOtherScriptsAreAccepted() {
        assertEquals("राहुल", DinerIdentity.normalizeName("राहुल"));
        assertEquals("عامر", DinerIdentity.normalizeName("عامر"));
        assertEquals("O'Brien-Smith Jr.", DinerIdentity.normalizeName("O'Brien-Smith Jr."));
    }

    @Test
    void emptyOrBlankNamesAreRefused() {
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName(null));
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName(""));
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("   \t "));
    }

    @Test
    void tooLongNamesAreRefused() {
        assertEquals("a".repeat(40), DinerIdentity.normalizeName("a".repeat(40)));
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("a".repeat(41)));
    }

    @Test
    void markupControlAndInvisibleCharactersAreRefused() {
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("<script>alert(1)</script>"));
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("Rahul\u0000"));
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("Ra\u200Bhul"));
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("Rahul 😀"));
    }

    @Test
    void parenthesesAreRefusedSoNobodyCanPassForANumberedDuplicate() {
        assertThrows(BadRequestException.class, () -> DinerIdentity.normalizeName("Rahul (2)"));
    }

    @Test
    void aFreeNameIsKeptAsTyped() {
        assertEquals("Rahul", DinerIdentity.uniqueDisplayName("Rahul", List.of("Amit", "Priya")));
        assertEquals("Rahul", DinerIdentity.uniqueDisplayName("Rahul", List.of()));
    }

    @Test
    void aTakenNameGetsTheNextFreeNumber_ignoringCase() {
        assertEquals("Rahul (2)", DinerIdentity.uniqueDisplayName("Rahul", List.of("Rahul")));
        assertEquals("rahul (2)", DinerIdentity.uniqueDisplayName("rahul", List.of("Rahul")));
        assertEquals("Rahul (3)", DinerIdentity.uniqueDisplayName("Rahul", List.of("Rahul", "Rahul (2)")));
        assertEquals("Rahul (2)", DinerIdentity.uniqueDisplayName("Rahul", List.of("Rahul", "Rahul (3)")));
    }

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = DinerIdentity.newToken();
            assertEquals(43, token.length());
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
            assertTrue(seen.add(token), "tokens must not repeat");
        }
    }

    @Test
    void theStoredHashIsStableHexAndIsNotTheToken() {
        String token = DinerIdentity.newToken();
        String hash = DinerIdentity.hash(token);

        assertEquals(hash, DinerIdentity.hash(token));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertNotEquals(token, hash);
        assertNotEquals(hash, DinerIdentity.hash(DinerIdentity.newToken()));
        // A published SHA-256 test vector, so the algorithm itself is pinned.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DinerIdentity.hash("abc"));
    }
}
