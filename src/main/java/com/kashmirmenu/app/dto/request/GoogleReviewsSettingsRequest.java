package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * Owner-supplied Google Place ID (find it with Google's Place ID Finder). Blank clears it.
 */
public record GoogleReviewsSettingsRequest(
        @Pattern(
                regexp = "^$|^[A-Za-z0-9_-]{10,255}$",
                message = "Enter a Google Place ID such as ChIJN1t_tDeuEmsRUsoyG83frY4")
        String googlePlaceId) {
}
