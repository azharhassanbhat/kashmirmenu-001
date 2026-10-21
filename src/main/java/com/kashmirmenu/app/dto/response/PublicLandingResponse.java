package com.kashmirmenu.app.dto.response;

import java.util.List;

/**
 * Everything the QR landing page shows, for the table whose QR was scanned. Safe to serve unauthenticated: it
 * never carries the Wi-Fi password (that has its own table-scoped endpoint) or anything about other restaurants.
 * A field that is not configured is null / absent so the screen hides the action instead of showing a dead one.
 *
 * @param actions the enabled action keys in display order (REWARDS, REVIEW, MENU, WIFI, FEEDBACK, ASSISTANCE)
 */
public record PublicLandingResponse(
        boolean landingEnabled,
        Long restaurantId,
        String restaurantName,
        String logo,
        String primaryColor,
        String secondaryColor,
        String tableName,
        String tagline,
        String coverImageUrl,
        String announcement,
        Hours hours,
        Links links,
        Wifi wifi,
        Rewards rewards,
        List<String> actions) {

    /** {@code known} false means the restaurant publishes no hours: show none and claim nothing. */
    public record Hours(boolean known, boolean open, String label, List<Day> week) {
    }

    public record Day(String day, String hours) {
    }

    public record Links(
            String googleReviewUrl,
            String instagramUrl,
            String youtubeUrl,
            String facebookUrl,
            String websiteUrl,
            String directionsUrl,
            String phone,
            String phoneDial) {
    }

    /** The network's name and security only; the password is fetched separately when the guest asks for it. */
    public record Wifi(String ssid, String security, boolean hasPassword) {
    }

    public record Rewards(java.math.BigDecimal spendPerPoint, int redeemPoints, String rewardText, String terms) {
    }
}
