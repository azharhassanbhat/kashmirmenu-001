package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The links a restaurant may put on its landing page. The Google review link never needs a Place ID. */
class LandingLinksTest {

    private static void refused(Runnable r) {
        assertThrows(BadRequestException.class, r::run);
    }

    // ------------------------------------------------------------------ Google review link

    @Test
    void theShareLinkFromGoogleBusinessProfileIsAccepted() {
        assertEquals("https://g.page/r/CabcDEF123456/review",
                LandingLinks.googleReview("https://g.page/r/CabcDEF123456/review"));
    }

    @Test
    void otherRealGoogleReviewAddressesAreAccepted() {
        for (String ok : new String[]{
                "https://search.google.com/local/writereview?placeid=ChIJN1t_tDeuEmsRUsoyG83frY4",
                "https://www.google.com/maps/place/Kashmir+Cafe/@34.08,74.79,17z",
                "https://google.com/search?q=kashmir+cafe+reviews",
                "https://maps.google.com/maps?cid=123456",
                "https://www.google.co.in/maps/place/Kashmir+Cafe",
                "https://maps.app.goo.gl/AbCdEf12345",
                "https://goo.gl/maps/AbCdEf12345"}) {
            assertEquals(ok, LandingLinks.googleReview(ok), ok);
        }
    }

    @Test
    void aBlankLinkMeansNotSetAndIsNeverAnError() {
        assertNull(LandingLinks.googleReview(null));
        assertNull(LandingLinks.googleReview(""));
        assertNull(LandingLinks.googleReview("   "));
    }

    @Test
    void surroundingSpacesAreTrimmed() {
        assertEquals("https://g.page/r/Cabc123/review", LandingLinks.googleReview("  https://g.page/r/Cabc123/review \n"));
    }

    @Test
    void aGoogleLinkThatIsNotReviewOrMapsShapedIsRefused() {
        refused(() -> LandingLinks.googleReview("https://g.page/somepage"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/"));
        refused(() -> LandingLinks.googleReview("https://search.google.com/search?q=x"));
        refused(() -> LandingLinks.googleReview("https://www.google.com/"));
        refused(() -> LandingLinks.googleReview("https://goo.gl/abc123"));
    }

    @Test
    void googleRedirectorsCannotSmuggleAnotherSiteIn() {
        refused(() -> LandingLinks.googleReview("https://www.google.com/url?q=https://evil.example"));
        refused(() -> LandingLinks.googleReview("https://www.google.com/amp/evil.example/page"));
    }

    @Test
    void pathTricksCannotReachGoogleRedirectorsThroughAnAllowedPrefix() {
        for (String bad : new String[]{
                "https://www.google.com/maps/../url?q=https://evil.example",
                "https://www.google.com/maps/%2e%2e/url?q=https://evil.example",
                "https://www.google.com/maps/..%2f..%2furl?q=https://evil.example",
                "https://www.google.com/maps/%2E%2E/url",
                "https://www.google.com/maps/./place",
                "https://www.google.com/maps//evil.example",
                "https://www.google.com/maps%5c..%5curl",
                "https://search.google.com/local/writereview/../../url?q=x",
                "https://g.page/r/../url/review",
                "https://maps.app.goo.gl/../url?q=https://evil.example",
                "https://goo.gl/maps/../url?q=https://evil.example"}) {
            refused(() -> LandingLinks.googleReview(bad));
        }
    }

    @Test
    void shortLinksMustBeASingleOpaqueCode() {
        assertNotNull(LandingLinks.googleReview("https://maps.app.goo.gl/AbCdEf123"));
        assertNotNull(LandingLinks.googleReview("https://maps.app.goo.gl/AbCdEf123?g_st=ic"));
        assertNotNull(LandingLinks.googleReview("https://goo.gl/maps/AbCdEf123"));
        refused(() -> LandingLinks.googleReview("https://maps.app.goo.gl/a/b/c"));
        refused(() -> LandingLinks.googleReview("https://maps.app.goo.gl/ab"));
        refused(() -> LandingLinks.googleReview("https://maps.app.goo.gl/"));
        refused(() -> LandingLinks.googleReview("https://goo.gl/maps/"));
    }

    @Test
    void gPageNeedsTheReviewEnding() {
        assertNotNull(LandingLinks.googleReview("https://g.page/r/CabcDEF123/review"));
        assertNotNull(LandingLinks.googleReview("https://g.page/r/CabcDEF123/review/"));
        assertNotNull(LandingLinks.googleReview("https://g.page/KashmirCafe/review"));
        assertNotNull(LandingLinks.googleReview("https://g.page/r/CabcDEF123/review?gm"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/CabcDEF123/share"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/CabcDEF123"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/a/b/review"));
    }

    @Test
    void anImFeelingLuckySearchCannotSendGuestsToAnUncheckedSite() {
        refused(() -> LandingLinks.googleReview("https://www.google.com/search?q=kashmir+cafe&btnI=1"));
        refused(() -> LandingLinks.googleReview("https://www.google.com/search?q=x&btni"));
        // an ordinary search is still fine, and a query word that merely contains the letters is not confused
        assertNotNull(LandingLinks.googleReview("https://www.google.com/search?q=kashmir+cafe+reviews"));
        assertNotNull(LandingLinks.googleReview("https://www.google.com/search?q=btnIsh+cafe"));
    }

    @Test
    void lookAlikeHostsAreRefused() {
        for (String bad : new String[]{
                "https://evilgoogle.com/maps/place/x",
                "https://google.com.evil.example/maps/place/x",
                "https://www.google.com.evil.example/maps",
                "https://g.page.evil.example/r/abc/review",
                "https://notg.page/r/abc/review",
                "https://maps.app.goo.gl.evil.example/abc",
                "https://example.com/maps/place/x"}) {
            refused(() -> LandingLinks.googleReview(bad));
        }
    }

    @Test
    void aCredentialsTrickIsRefused() {
        refused(() -> LandingLinks.googleReview("https://g.page@evil.example/r/abc/review"));
        refused(() -> LandingLinks.googleReview("https://user:pw@g.page/r/abc/review"));
    }

    @Test
    void onlyHttpsIsAccepted() {
        refused(() -> LandingLinks.googleReview("http://g.page/r/abc/review"));
        refused(() -> LandingLinks.googleReview("javascript:alert(1)"));
        refused(() -> LandingLinks.googleReview("data:text/html,<script>alert(1)</script>"));
        refused(() -> LandingLinks.googleReview("//g.page/r/abc/review"));
        refused(() -> LandingLinks.googleReview("g.page/r/abc/review"));
        refused(() -> LandingLinks.googleReview("ftp://g.page/r/abc/review"));
    }

    @Test
    void whitespaceQuotesAndControlCharactersInsideALinkAreRefused() {
        refused(() -> LandingLinks.googleReview("https://g.page/r/abc/review one"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/abc/review\"onclick=\"x"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/abc/review<script>"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/abc/review\u0000"));
        refused(() -> LandingLinks.googleReview("https://g.page/r/abc\\review"));
    }

    @Test
    void anOverlongLinkIsRefused() {
        refused(() -> LandingLinks.googleReview("https://g.page/r/" + "a".repeat(LandingLinks.MAX_LENGTH) + "/review"));
    }

    @Test
    void aPlaceIdAloneIsNotAReviewLink() {
        refused(() -> LandingLinks.googleReview("ChIJN1t_tDeuEmsRUsoyG83frY4"));
    }

    @Test
    void storedValuesAreRecheckedBeforeBeingShown() {
        assertTrue(LandingLinks.isSafeGoogleReview("https://g.page/r/abc123/review"));
        assertFalse(LandingLinks.isSafeGoogleReview("https://evil.example/r/abc/review"));
        assertFalse(LandingLinks.isSafeGoogleReview(null));
        assertFalse(LandingLinks.isSafeGoogleReview(""));
    }

    // ------------------------------------------------------------------ social and other links

    @Test
    void socialLinksMustBeOnTheirOwnSite() {
        assertEquals("https://www.instagram.com/kashmirmenu", LandingLinks.instagram("https://www.instagram.com/kashmirmenu"));
        assertEquals("https://youtube.com/@kashmirmenu", LandingLinks.youtube("https://youtube.com/@kashmirmenu"));
        assertEquals("https://youtu.be/abc123", LandingLinks.youtube("https://youtu.be/abc123"));
        assertEquals("https://www.facebook.com/kashmirmenu", LandingLinks.facebook("https://www.facebook.com/kashmirmenu"));
        refused(() -> LandingLinks.instagram("https://evil.example/kashmirmenu"));
        refused(() -> LandingLinks.instagram("https://instagram.com.evil.example/x"));
        refused(() -> LandingLinks.youtube("https://www.instagram.com/x"));
        refused(() -> LandingLinks.facebook("http://facebook.com/x"));
        assertNull(LandingLinks.instagram(""));
        assertNull(LandingLinks.youtube(null));
    }

    @Test
    void websiteAndMapLinksAcceptAnyPublicHttpsAddress() {
        assertEquals("https://kashmirmenu.example/about", LandingLinks.https("https://kashmirmenu.example/about", "website"));
        assertNull(LandingLinks.https(" ", "website"));
        refused(() -> LandingLinks.https("http://kashmirmenu.example", "website"));
        refused(() -> LandingLinks.https("https://localhost/x", "website"));
        refused(() -> LandingLinks.https("https://nodots/x", "website"));
        refused(() -> LandingLinks.https("javascript:alert(1)", "website"));
    }
}
