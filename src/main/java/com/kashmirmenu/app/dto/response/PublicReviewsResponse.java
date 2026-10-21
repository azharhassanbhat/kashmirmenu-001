package com.kashmirmenu.app.dto.response;

import java.util.List;

/**
 * What the diner screens may show about a restaurant's Google reviews.
 *
 * @param writeReviewUrl opens Google's own "write a review" dialog; null when the
 *                       restaurant has not set a Place ID
 * @param rating         null when the Places API is not configured or unavailable
 */
public record PublicReviewsResponse(
        String writeReviewUrl,
        String mapsUrl,
        Double rating,
        Integer ratingCount,
        List<Review> reviews) {

    /** Author attribution must be shown with each review (Google Maps Platform policy). */
    public record Review(
            String authorName,
            String authorUrl,
            String authorPhotoUrl,
            Integer rating,
            String text,
            String relativeTime,
            String reviewUrl) {
    }

    /** No rating available (no API key, or Google unreachable): links only. */
    public static PublicReviewsResponse linkOnly(String writeReviewUrl, String mapsUrl) {
        return new PublicReviewsResponse(writeReviewUrl, mapsUrl, null, null, List.of());
    }
}
