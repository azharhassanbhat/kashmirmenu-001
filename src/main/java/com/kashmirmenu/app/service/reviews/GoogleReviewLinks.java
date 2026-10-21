package com.kashmirmenu.app.service.reviews;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds Google's documented "write a review" link from a Place ID. */
public final class GoogleReviewLinks {

    private GoogleReviewLinks() {
    }

    public static String writeReviewUrl(String placeId) {
        if (placeId == null || placeId.isBlank()) {
            return null;
        }
        return "https://search.google.com/local/writereview?placeid="
                + URLEncoder.encode(placeId.trim(), StandardCharsets.UTF_8);
    }

    /**
     * Opens the place (and its reviews) in Google Maps, using the documented Maps URLs
     * format; query_place_id wins, the name is only a fallback. Needs no API key.
     */
    public static String mapsUrl(String placeId, String restaurantName) {
        if (placeId == null || placeId.isBlank()) {
            return null;
        }
        String query = restaurantName == null || restaurantName.isBlank() ? "restaurant" : restaurantName;
        return "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&query_place_id="
                + URLEncoder.encode(placeId.trim(), StandardCharsets.UTF_8);
    }
}
