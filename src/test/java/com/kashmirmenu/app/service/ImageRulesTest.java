package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Photo addresses, the storage quota, and which image links a dish may carry. */
class ImageRulesTest {

    // ------------------------------------------------------------------ keys

    @Test
    void aKeyIs32LowercaseHexCharactersAndNeverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String k = ImageKeys.newKey();
            assertTrue(ImageKeys.isValid(k), k);
            assertEquals(32, k.length());
            assertTrue(seen.add(k), "a key repeated");
        }
    }

    @Test
    void onlyWellFormedKeysAreValid() {
        assertTrue(ImageKeys.isValid("0123456789abcdef0123456789abcdef"));
        assertFalse(ImageKeys.isValid(null));
        assertFalse(ImageKeys.isValid(""));
        assertFalse(ImageKeys.isValid("0123456789ABCDEF0123456789ABCDEF"));       // capitals
        assertFalse(ImageKeys.isValid("0123456789abcdef0123456789abcde"));        // 31
        assertFalse(ImageKeys.isValid("0123456789abcdef0123456789abcdef0"));      // 33
        assertFalse(ImageKeys.isValid("../../etc/passwd0123456789abcdef"));
        assertFalse(ImageKeys.isValid("0123456789abcdef0123456789abcde%"));
    }

    @Test
    void anAddressRoundTripsToItsKey() {
        String key = ImageKeys.newKey();
        assertEquals("/api/public/images/" + key, ImageKeys.urlFor(key));
        assertEquals(key, ImageKeys.keyFrom(ImageKeys.urlFor(key)));
    }

    @Test
    void anythingThatIsNotOurAddressHasNoKey() {
        assertNull(ImageKeys.keyFrom(null));
        assertNull(ImageKeys.keyFrom("https://example.com/a.jpg"));
        assertNull(ImageKeys.keyFrom("/api/public/images/short"));
        assertNull(ImageKeys.keyFrom("/api/public/images/0123456789abcdef0123456789abcdef/extra"));
        assertNull(ImageKeys.keyFrom("/api/public/images/"));
    }

    // ------------------------------------------------------------------ quota

    @Test
    void aRestaurantWithRoomMayAddAPhoto() {
        assertDoesNotThrow(() -> ImageQuota.check(0, 0, 100_000));
        assertDoesNotThrow(() -> ImageQuota.check(ImageQuota.MAX_BYTES - 1000, 5, 1000));   // exactly full is allowed
    }

    @Test
    void goingOverTheByteLimitIsRefusedWithAHelpfulMessage() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> ImageQuota.check(ImageQuota.MAX_BYTES - 999, 5, 1000));
        assertTrue(e.getMessage().contains("storage is full"));
        assertTrue(e.getMessage().contains("cleared automatically"));
    }

    @Test
    void goingOverThePhotoCountIsRefused() {
        assertDoesNotThrow(() -> ImageQuota.check(0, ImageQuota.MAX_COUNT - 1, 1000));
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> ImageQuota.check(0, ImageQuota.MAX_COUNT, 1000));
        assertTrue(e.getMessage().contains("limit"));
    }

    // ------------------------------------------------------------------ links

    @Test
    void blankLinksAreFineBecauseAPhotoIsOptional() {
        assertNull(ImageUrlRules.problem(null));
        assertNull(ImageUrlRules.problem(""));
        assertNull(ImageUrlRules.problem("   "));
    }

    @Test
    void anHttpsLinkIsAccepted() {
        assertNull(ImageUrlRules.problem("https://example.com/dish.jpg"));
        assertNull(ImageUrlRules.problem("HTTPS://Example.com/dish"));
        assertNull(ImageUrlRules.problem("https://res.cloudinary.com/demo/image/upload/v1/sample.png?w=400"));
        assertNull(ImageUrlRules.problem("  https://example.com/dish.jpg  "));
    }

    @Test
    void anUploadedPhotoOfOursIsAccepted() {
        assertNull(ImageUrlRules.problem(ImageKeys.urlFor(ImageKeys.newKey())));
    }

    @Test
    void insecureOrDangerousAddressesAreRefused() {
        for (String bad : new String[]{"http://example.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
                "ftp://example.com/a.jpg", "//example.com/a.jpg", "example.com/a.jpg", "file:///etc/passwd",
                "vbscript:x", "/etc/passwd", "../a.jpg"}) {
            assertNotNull(ImageUrlRules.problem(bad), bad);
        }
    }

    @Test
    void malformedAddressesAreRefused() {
        assertNotNull(ImageUrlRules.problem("https://"));
        assertNotNull(ImageUrlRules.problem("https:///path"));
        assertNotNull(ImageUrlRules.problem("https://exa mple.com/a.jpg"));
        assertNotNull(ImageUrlRules.problem("https://user:pass@example.com/a.jpg"));
        assertNotNull(ImageUrlRules.problem("https://example.com/a\nb.jpg"));
        assertNotNull(ImageUrlRules.problem("https://example.com/" + "a".repeat(500)));
    }

    @Test
    void internationalisedDomainNamesAreAcceptedBecauseBrowsersLoadThem() {
        assertNull(ImageUrlRules.problem("https://\u4f8b\u3048.jp/a.jpg"));                       // Japanese
        assertNull(ImageUrlRules.problem("https://m\u00fcnchen.de/a.jpg"));
        assertNull(ImageUrlRules.problem("https://\u092d\u093e\u0930\u0924.example/a.jpg"));  // Devanagari
        assertNull(ImageUrlRules.problem("https://\u0645\u062b\u0627\u0644.\u0627\u062e\u062a\u0628\u0627\u0631/a.jpg")); // Arabic script
        assertNull(ImageUrlRules.problem("https://\u4f8b\u3048.jp:8443/a.jpg"));
        assertNotNull(ImageUrlRules.problem("https://\u4f8b\u3048 .jp/a.jpg"));                  // a space is still refused
    }

    @Test
    void anAddressWithNoHostIsRefusedHoweverManySlashesFollowHttps() {
        assertNotNull(ImageUrlRules.problem("https://"));
        assertNotNull(ImageUrlRules.problem("https:///path"));
        assertNotNull(ImageUrlRules.problem("https:////path"));
        assertNotNull(ImageUrlRules.problem("https://?x=1"));
        assertNotNull(ImageUrlRules.problem("https://#frag"));
    }

    @Test
    void anUploadAddressThatIsNotRealIsRefused() {
        assertNotNull(ImageUrlRules.problem("/api/public/images/notakey"));
        assertNotNull(ImageUrlRules.problem("/api/public/images/../../x"));
    }

    @Test
    void anUnchangedAddressIsNeverCheckedSoAnOldLinkCannotBlockAnUnrelatedEdit() {
        assertDoesNotThrow(() -> ImageUrlRules.requireValidIfChanged("http://old.example.com/a.jpg", "http://old.example.com/a.jpg"));
        assertDoesNotThrow(() -> ImageUrlRules.requireValidIfChanged("  http://old.example.com/a.jpg ", "http://old.example.com/a.jpg"));
        assertDoesNotThrow(() -> ImageUrlRules.requireValidIfChanged(null, null));
        assertDoesNotThrow(() -> ImageUrlRules.requireValidIfChanged("", null));
    }

    @Test
    void aChangedAddressIsChecked() {
        assertThrows(BadRequestException.class, () -> ImageUrlRules.requireValidIfChanged("http://new.example.com/a.jpg", "https://old.example.com/a.jpg"));
        assertThrows(BadRequestException.class, () -> ImageUrlRules.requireValidIfChanged("javascript:1", null));
        assertDoesNotThrow(() -> ImageUrlRules.requireValidIfChanged("https://new.example.com/a.jpg", "http://old.example.com/a.jpg"));
        assertDoesNotThrow(() -> ImageUrlRules.requireValidIfChanged(null, "http://old.example.com/a.jpg"));   // removing is always fine
    }
}
