package com.kashmirmenu.app.security;

import com.kashmirmenu.app.config.RateLimitProperties;
import com.kashmirmenu.app.security.filter.AuthRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * How the public limits behave. The point of the per-guest limit: every phone in a restaurant shares one
 * wifi address, so one busy table must not be able to use up the allowance of everyone else.
 */
class AuthRateLimitFilterTest {

    private final RateLimitProperties props = new RateLimitProperties();
    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(props);

    private int send(String method, String path, String ip, String guestToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(ip);
        if (guestToken != null) {
            request.addHeader("X-Diner-Token", guestToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private int addToCart(String ip, String token) throws Exception {
        return send("POST", "/api/public/tables/qr-1/cart/items", ip, token);
    }

    @Test
    void oneGuestUsingTheirAllowanceDoesNotLockOutTheOthersAtTheSameAddress() throws Exception {
        props.setPublicLimit(1000);
        props.setPublicGuestLimit(3);

        for (int i = 0; i < 3; i++) {
            assertEquals(200, addToCart("10.0.0.1", "guest-a"));
        }
        assertEquals(429, addToCart("10.0.0.1", "guest-a"), "the busy guest is slowed down");

        // Others on the same wifi are untouched.
        assertEquals(200, addToCart("10.0.0.1", "guest-b"));
        assertEquals(200, addToCart("10.0.0.1", "guest-c"));
    }

    @Test
    void theAddressCeilingStillStopsAFloodFromOneAddress() throws Exception {
        props.setPublicLimit(3);
        props.setPublicGuestLimit(1000);

        assertEquals(200, addToCart("10.0.0.1", "guest-a"));
        assertEquals(200, addToCart("10.0.0.1", "guest-b"));
        assertEquals(200, addToCart("10.0.0.1", "guest-c"));
        assertEquals(429, addToCart("10.0.0.1", "guest-d"), "many tokens do not get round the ceiling");

        // A different address is unaffected.
        assertEquals(200, addToCart("10.0.0.2", "guest-e"));
    }

    @Test
    void joiningATableCarriesNoTokenSoOnlyTheAddressLimitApplies() throws Exception {
        props.setPublicLimit(2);
        props.setPublicGuestLimit(1);

        assertEquals(200, send("POST", "/api/public/tables/qr-1/join", "10.0.0.1", null));
        assertEquals(200, send("POST", "/api/public/tables/qr-1/join", "10.0.0.1", null));
        assertEquals(429, send("POST", "/api/public/tables/qr-1/join", "10.0.0.1", null));
    }

    @Test
    void readingACartOrBillIsNeverLimited() throws Exception {
        props.setPublicLimit(1);
        props.setPublicGuestLimit(1);

        for (int i = 0; i < 20; i++) {
            assertEquals(200, send("GET", "/api/public/tables/qr-1/cart", "10.0.0.1", "guest-a"));
            assertEquals(200, send("GET", "/api/public/tables/qr-1/bill", "10.0.0.1", "guest-a"));
            assertEquals(200, send("GET", "/api/public/tables/qr-1/settlements", "10.0.0.1", "guest-a"));
        }
    }

    @Test
    void orderTrackingReadsHaveTheirOwnAllowanceSoPollingCannotBlockOrdering() throws Exception {
        props.setPublicLimit(2);
        props.setPublicTrackLimit(50);

        // Use up the allowance for writes.
        assertEquals(200, addToCart("10.0.0.1", null));
        assertEquals(200, addToCart("10.0.0.1", null));
        assertEquals(429, addToCart("10.0.0.1", null));

        // Every guest's order screen polling from the same address still works.
        for (int i = 0; i < 50; i++) {
            assertEquals(200, send("GET", "/api/public/orders/by-token/abc", "10.0.0.1", null));
        }
        assertEquals(429, send("GET", "/api/public/orders/by-token/abc", "10.0.0.1", null),
                "but token guessing at speed is still stopped");
    }

    @Test
    void signInKeepsItsOwnTighterLimit() throws Exception {
        props.setAuthLimit(2);

        assertEquals(200, send("POST", "/api/v1/auth/login", "10.0.0.1", null));
        assertEquals(200, send("POST", "/api/v1/auth/login", "10.0.0.1", null));
        assertEquals(429, send("POST", "/api/v1/auth/login", "10.0.0.1", null));
    }

    @Test
    void aRefusalTellsTheClientWhenToTryAgain() throws Exception {
        props.setPublicLimit(1);

        send("POST", "/api/public/tables/qr-1/join", "10.0.0.1", null);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/tables/qr-1/join");
        request.setServletPath("/api/public/tables/qr-1/join");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
    }

    // ------------------------------------------------------------------ landing-page writes

    private int feedback(String qr, String ip) throws Exception {
        return send("POST", "/api/public/tables/" + qr + "/anonymous-feedback", ip, null);
    }

    private int rewards(String qr, String ip, String action) throws Exception {
        return send("POST", "/api/public/tables/" + qr + "/rewards/" + action, ip, null);
    }

    @Test
    void feedbackIsLimitedPerTableSoOneChattyTableDoesNotLockOutTheRoom() throws Exception {
        props.setPublicLimit(1000);
        props.setLandingFeedbackLimit(3);

        for (int i = 0; i < 3; i++) {
            assertEquals(200, feedback("qr-1", "10.0.0.1"));
        }
        assertEquals(429, feedback("qr-1", "10.0.0.1"), "the flood is refused");

        // Another table behind the same wifi address is untouched, and so is another address at the same table.
        assertEquals(200, feedback("qr-2", "10.0.0.1"));
        assertEquals(200, feedback("qr-1", "10.0.0.2"));
    }

    @Test
    void rewardsWritesHaveTheirOwnAllowanceSeparateFromFeedback() throws Exception {
        props.setPublicLimit(1000);
        props.setLandingFeedbackLimit(1);
        props.setLandingRewardsLimit(2);

        assertEquals(200, feedback("qr-1", "10.0.0.1"));
        assertEquals(429, feedback("qr-1", "10.0.0.1"));

        assertEquals(200, rewards("qr-1", "10.0.0.1", "join"));
        assertEquals(200, rewards("qr-1", "10.0.0.1", "claim"));
        assertEquals(429, rewards("qr-1", "10.0.0.1", "claim"));
    }

    @Test
    void landingLimitsDoNotUseUpTheAllowanceForOrdering() throws Exception {
        props.setPublicLimit(1000);
        props.setPublicGuestLimit(1000);
        props.setLandingFeedbackLimit(1);

        assertEquals(200, feedback("qr-1", "10.0.0.1"));
        assertEquals(429, feedback("qr-1", "10.0.0.1"));

        assertEquals(200, addToCart("10.0.0.1", "guest-a"), "diners can still order");
    }

    @Test
    void landingWritesAlsoCountAgainstTheGenericAddressCeiling() throws Exception {
        props.setPublicLimit(2);
        props.setLandingFeedbackLimit(100);

        assertEquals(200, feedback("qr-1", "10.0.0.1"));
        assertEquals(200, feedback("qr-2", "10.0.0.1"));
        assertEquals(429, feedback("qr-3", "10.0.0.1"), "many tables do not get round the address ceiling");
    }

    @Test
    void readingTheLandingPageWifiAndRewardsIsNeverLimited() throws Exception {
        props.setPublicLimit(1);
        props.setLandingFeedbackLimit(1);
        props.setLandingRewardsLimit(1);

        for (int i = 0; i < 20; i++) {
            assertEquals(200, send("GET", "/api/public/tables/qr-1/landing", "10.0.0.1", null));
            assertEquals(200, send("GET", "/api/public/tables/qr-1/wifi", "10.0.0.1", null));
            assertEquals(200, send("GET", "/api/public/tables/qr-1/wifi/qr", "10.0.0.1", null));
            assertEquals(200, send("GET", "/api/public/tables/qr-1/rewards", "10.0.0.1", null));
        }
    }

    @Test
    void aLandingRefusalTellsTheClientTheLandingWindow() throws Exception {
        props.setPublicLimit(1000);
        props.setLandingFeedbackLimit(1);
        props.setLandingWindowSeconds(600);
        props.setAuthWindowSeconds(60);

        feedback("qr-1", "10.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/tables/qr-1/anonymous-feedback");
        request.setServletPath("/api/public/tables/qr-1/anonymous-feedback");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertEquals("600", response.getHeader("Retry-After"));
    }

    @Test
    void lookAlikePathsAreNotMistakenForLandingWrites() throws Exception {
        props.setPublicLimit(1000);
        props.setLandingFeedbackLimit(1);
        props.setLandingRewardsLimit(1);

        // Not the feedback or rewards endpoints, so they only face the ordinary public limits.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("POST", "/api/public/tables/qr-1/anonymous-feedback-extra", "10.0.0.1", null));
            assertEquals(200, send("POST", "/api/public/tables/qr-1/rewardsx/join", "10.0.0.1", null));
            assertEquals(200, send("POST", "/api/public/orders/by-token/abc/feedback", "10.0.0.1", null));
        }
    }
}
