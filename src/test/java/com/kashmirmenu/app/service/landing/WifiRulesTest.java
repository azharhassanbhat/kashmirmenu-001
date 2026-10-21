package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.landing.WifiRules.Security;
import com.kashmirmenu.app.service.landing.WifiRules.Wifi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WifiRulesTest {

    private static void refused(Runnable r, String fragment) {
        BadRequestException e = assertThrows(BadRequestException.class, r::run);
        assertTrue(e.getMessage().toLowerCase().contains(fragment.toLowerCase()), e.getMessage());
    }

    @Test
    void aNormalWpaNetworkIsAccepted() {
        Wifi w = WifiRules.validate("Kashmir Cafe", "wpa", "chinar-2026");
        assertEquals("Kashmir Cafe", w.ssid());
        assertEquals(Security.WPA, w.security());
        assertEquals("chinar-2026", w.password());
    }

    @Test
    void anOpenNetworkKeepsNoPassword() {
        Wifi w = WifiRules.validate("Guest", "NONE", "left-over-password");
        assertEquals(Security.NONE, w.security());
        assertNull(w.password());
    }

    @Test
    void aMissingSecurityTypeMeansWpa() {
        assertEquals(Security.WPA, WifiRules.validate("Guest", null, "password1").security());
        assertEquals(Security.WPA, WifiRules.validate("Guest", " ", "password1").security());
    }

    @Test
    void anUnknownSecurityTypeIsRefused() {
        refused(() -> WifiRules.validate("Guest", "WPA9", "password1"), "WPA, WEP or NONE");
    }

    @Test
    void theNetworkNameIsRequiredAndBoundedInBytes() {
        refused(() -> WifiRules.validate("  ", "WPA", "password1"), "network name");
        refused(() -> WifiRules.validate(null, "WPA", "password1"), "network name");
        assertEquals(32, WifiRules.validate("a".repeat(32), "WPA", "password1").ssid().length());
        refused(() -> WifiRules.validate("a".repeat(33), "WPA", "password1"), "32 bytes");
        // 11 three-byte characters = 33 bytes although only 11 characters
        refused(() -> WifiRules.validate("\u20AC".repeat(11), "WPA", "password1"), "32 bytes");
    }

    @Test
    void passwordLengthsFollowTheSecurityType() {
        refused(() -> WifiRules.validate("Guest", "WPA", "short"), "8 to 63");
        refused(() -> WifiRules.validate("Guest", "WPA", "x".repeat(64)), "8 to 63");
        assertNotNull(WifiRules.validate("Guest", "WPA", "x".repeat(63)));
        refused(() -> WifiRules.validate("Guest", "WEP", "abcd"), "5 to 26");
        assertNotNull(WifiRules.validate("Guest", "WEP", "abcde"));
        refused(() -> WifiRules.validate("Guest", "WPA", null), "8 to 63");
    }

    @Test
    void controlCharactersAreRefused() {
        refused(() -> WifiRules.validate("Gu\nest", "WPA", "password1"), "not allowed");
        refused(() -> WifiRules.validate("Guest", "WPA", "pass\u0000word1"), "not allowed");
    }

    @Test
    void theQrPayloadIsTheStandardFormat() {
        assertEquals("WIFI:T:WPA;S:Kashmir Cafe;P:chinar-2026;;",
                WifiRules.qrPayload("Kashmir Cafe", Security.WPA, "chinar-2026"));
        assertEquals("WIFI:T:WEP;S:Old;P:abcde;;", WifiRules.qrPayload("Old", Security.WEP, "abcde"));
        assertEquals("WIFI:T:nopass;S:Open;;", WifiRules.qrPayload("Open", Security.NONE, null));
    }

    @Test
    void reservedCharactersAreEscapedSoTheyCannotBreakTheFormat() {
        assertEquals("WIFI:T:WPA;S:a\\;b\\,c\\:d\\\"e\\\\f;P:p\\;w\\:x;;",
                WifiRules.qrPayload("a;b,c:d\"e\\f", Security.WPA, "p;w:x"));
    }

    @Test
    void aPasswordCannotInjectAnotherField() {
        // an attacker-chosen password trying to close the field and add a hidden network flag
        String payload = WifiRules.qrPayload("Guest", Security.WPA, "abcdefgh;;H:true");
        assertEquals("WIFI:T:WPA;S:Guest;P:abcdefgh\\;\\;H\\:true;;", payload);
    }

    @Test
    void anOpenNetworkNeverCarriesAPasswordEvenIfOneIsPassedIn() {
        assertFalse(WifiRules.qrPayload("Open", Security.NONE, "secret").contains("secret"));
    }
}
