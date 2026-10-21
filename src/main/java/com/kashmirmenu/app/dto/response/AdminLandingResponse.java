package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The landing settings as the owner edits them. The Wi-Fi password is never included: only whether one is
 * saved, so it cannot leak through this response or be read back by a manager's browser.
 */
public record AdminLandingResponse(
        boolean landingEnabled,
        String tagline,
        String coverImageUrl,
        String announcement,
        String openingHours,
        String googleReviewUrl,
        String instagramUrl,
        String youtubeUrl,
        String facebookUrl,
        String websiteUrl,
        String mapUrl,
        boolean wifiEnabled,
        String wifiSsid,
        String wifiSecurity,
        boolean wifiPasswordSet,
        boolean feedbackEnabled,
        boolean assistanceEnabled,
        boolean rewardsEnabled,
        BigDecimal rewardsSpendPerPoint,
        int rewardsRedeemPoints,
        String rewardsRewardText,
        String rewardsTerms,
        List<String> actions,
        List<String> allActions) {
}
