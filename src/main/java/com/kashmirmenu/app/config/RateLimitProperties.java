package com.kashmirmenu.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** Max requests per client IP to /api/v1/auth/** within the window. */
    private int authLimit = 20;

    private int authWindowSeconds = 60;

    /**
     * Max mutating requests per client IP to /api/public/** within the auth window. A ceiling against
     * abuse, deliberately generous: every phone in a restaurant shares one wifi address, so this has to
     * allow a full dining room. The limit guests meet day to day is publicGuestLimit.
     */
    private int publicLimit = 600;

    /**
     * Max order-tracking reads (GET /api/public/orders/**) per client IP within the auth window. Higher
     * than publicLimit because every guest's order screen polls every few seconds; it exists to stop
     * order tokens being guessed at speed, not to ration normal use.
     */
    private int publicTrackLimit = 1200;

    /**
     * Landing-page feedback: max submissions per client IP per table QR within {@link #landingWindowSeconds}.
     * Per table, so a dining room sharing one wifi address is not locked out by one chatty table.
     */
    private int landingFeedbackLimit = 5;

    /** Rewards writes (join, claim) per client IP per table QR within the landing window. */
    private int landingRewardsLimit = 20;

    private int landingWindowSeconds = 600;

    /** Max mutating requests per guest (per X-Diner-Token) within the auth window. */
    private int publicGuestLimit = 60;

    /** Failed sign-ins allowed per account (email) within {@link #loginFailureWindowSeconds}, from any address. */
    private int loginFailureLimit = 10;

    private int loginFailureWindowSeconds = 900;

    /**
     * Only enable if this app sits behind a trusted reverse proxy that sets
     * (and strips any client-supplied) X-Forwarded-For.
     */
    private boolean trustForwardedHeader = false;
}