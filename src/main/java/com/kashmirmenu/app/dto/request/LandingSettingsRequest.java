package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The owner's landing-page settings. Link fields are validated in {@code LandingLinks} (https only, the right
 * site for each), hours in {@code OpeningHours}, Wi-Fi in {@code WifiRules}. A null {@code wifiPassword} leaves
 * the saved password as it is, so the form never has to be given the password back to save other changes.
 */
public record LandingSettingsRequest(
        Boolean landingEnabled,
        @Size(max = 120, message = "Keep the tagline under 120 characters.") String tagline,
        @Size(max = 500) String coverImageUrl,
        @Size(max = 300, message = "Keep the announcement under 300 characters.") String announcement,
        @Size(max = 700) String openingHours,
        @Size(max = 500) String googleReviewUrl,
        @Size(max = 500) String instagramUrl,
        @Size(max = 500) String youtubeUrl,
        @Size(max = 500) String facebookUrl,
        @Size(max = 500) String websiteUrl,
        @Size(max = 500) String mapUrl,
        Boolean wifiEnabled,
        @Size(max = 64) String wifiSsid,
        @Size(max = 8) String wifiSecurity,
        @Size(max = 63) String wifiPassword,
        Boolean feedbackEnabled,
        Boolean assistanceEnabled,
        Boolean rewardsEnabled,
        BigDecimal rewardsSpendPerPoint,
        Integer rewardsRedeemPoints,
        @Size(max = 150) String rewardsRewardText,
        @Size(max = 300) String rewardsTerms,
        /* Shown cards, in order; a key left out is hidden. Null keeps the default order with everything on. */
        List<String> actions) {
}
