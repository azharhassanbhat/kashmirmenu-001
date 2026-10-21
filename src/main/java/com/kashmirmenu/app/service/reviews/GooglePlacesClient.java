package com.kashmirmenu.app.service.reviews;

import com.fasterxml.jackson.databind.JsonNode;
import com.kashmirmenu.app.dto.response.PublicReviewsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a place's rating and most relevant reviews from the Places API (New).
 * Results are held briefly in memory so a busy dining room does not turn into one
 * paid API call per diner. Failures never break the menu: callers get null and
 * fall back to the review link.
 */
@Slf4j
@Component
public class GooglePlacesClient {

    private static final String FIELD_MASK = "rating,userRatingCount,googleMapsUri,reviews";
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(1);

    private final GooglePlacesProperties properties;
    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant expiresAt, PublicReviewsResponse value) {
    }

    public GooglePlacesClient(GooglePlacesProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMillis()));
        this.restClient = RestClient.builder()
                .baseUrl("https://places.googleapis.com/v1")
                .requestFactory(factory)
                .build();
    }

    /** @return rating and reviews, or null when disabled or unavailable */
    public PublicReviewsResponse fetch(String placeId, String writeReviewUrl) {
        if (!properties.isEnabled() || placeId == null || placeId.isBlank()) {
            return null;
        }
        Cached hit = cache.get(placeId);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.value();
        }
        try {
            JsonNode body = restClient.get()
                    .uri(uri -> uri.path("/places/{id}")
                            .queryParam("languageCode", properties.getLanguageCode())
                            .build(placeId))
                    .header("X-Goog-Api-Key", properties.getApiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .retrieve()
                    .body(JsonNode.class);
            PublicReviewsResponse value = parse(body, writeReviewUrl);
            cache.put(placeId, new Cached(
                    Instant.now().plus(Duration.ofMinutes(properties.getCacheMinutes())), value));
            return value;
        } catch (RuntimeException ex) {
            // Log without the key; back off briefly so an outage is not retried per request.
            log.warn("Google Places lookup failed for placeId={}: {}", placeId, ex.getMessage());
            cache.put(placeId, new Cached(Instant.now().plus(FAILURE_BACKOFF), null));
            return null;
        }
    }

    /** Maps a Place Details (New) response; package-private for tests. */
    static PublicReviewsResponse parse(JsonNode body, String writeReviewUrl) {
        if (body == null) {
            return null;
        }
        List<PublicReviewsResponse.Review> reviews = new ArrayList<>();
        for (JsonNode r : body.path("reviews")) {
            JsonNode author = r.path("authorAttribution");
            String text = r.path("text").path("text").asText(null);
            if (text == null) {
                text = r.path("originalText").path("text").asText(null);
            }
            reviews.add(new PublicReviewsResponse.Review(
                    author.path("displayName").asText("Google user"),
                    author.path("uri").asText(null),
                    author.path("photoUri").asText(null),
                    r.hasNonNull("rating") ? r.get("rating").asInt() : null,
                    text,
                    r.path("relativePublishTimeDescription").asText(null),
                    r.path("googleMapsUri").asText(null)));
        }
        return new PublicReviewsResponse(
                writeReviewUrl,
                body.path("googleMapsUri").asText(null),
                body.hasNonNull("rating") ? body.get("rating").asDouble() : null,
                body.hasNonNull("userRatingCount") ? body.get("userRatingCount").asInt() : null,
                reviews);
    }
}
