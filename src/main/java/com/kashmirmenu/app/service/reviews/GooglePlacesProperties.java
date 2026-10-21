package com.kashmirmenu.app.service.reviews;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.google-places")
public class GooglePlacesProperties {

    /**
     * Server-side Places API (New) key. Leave empty to disable rating display; the
     * "write a review" link works without it. Restrict the key to the Places API.
     */
    private String apiKey = "";

    /** How long a fetched rating is reused, which bounds API cost per restaurant. */
    private int cacheMinutes = 10;

    /** Preferred language for review text (BCP-47). */
    private String languageCode = "en";

    private int timeoutMillis = 3000;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
