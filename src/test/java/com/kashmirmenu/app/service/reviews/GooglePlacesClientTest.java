package com.kashmirmenu.app.service.reviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.dto.response.PublicReviewsResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GooglePlacesClientTest {

    @Test
    void parsesPlaceDetailsWithAttribution() throws Exception {
        String json = """
                {"rating":4.6,"userRatingCount":1240,"googleMapsUri":"https://maps.google.com/?cid=1",
                 "reviews":[{"rating":5,"relativePublishTimeDescription":"a week ago",
                   "text":{"text":"Best rogan josh in Srinagar","languageCode":"en"},
                   "authorAttribution":{"displayName":"Aisha K","uri":"https://www.google.com/maps/contrib/1",
                     "photoUri":"https://lh3.googleusercontent.com/a"},
                   "googleMapsUri":"https://www.google.com/maps/reviews/1"},
                  {"rating":4,"originalText":{"text":"Lovely kahwa"},"authorAttribution":{}}]}
                """;
        PublicReviewsResponse r = GooglePlacesClient.parse(new ObjectMapper().readTree(json), "https://w");

        assertThat(r.rating()).isEqualTo(4.6);
        assertThat(r.ratingCount()).isEqualTo(1240);
        assertThat(r.writeReviewUrl()).isEqualTo("https://w");
        assertThat(r.reviews()).hasSize(2);
        assertThat(r.reviews().get(0).authorName()).isEqualTo("Aisha K");
        assertThat(r.reviews().get(0).text()).isEqualTo("Best rogan josh in Srinagar");
        // falls back to originalText and a neutral author name
        assertThat(r.reviews().get(1).text()).isEqualTo("Lovely kahwa");
        assertThat(r.reviews().get(1).authorName()).isEqualTo("Google user");
    }

    @Test
    void disabledWithoutApiKey() {
        GooglePlacesClient client = new GooglePlacesClient(new GooglePlacesProperties());
        assertThat(client.fetch("ChIJN1t_tDeuEmsRUsoyG83frY4", "https://w")).isNull();
    }

    @Test
    void buildsEncodedWriteReviewLink() {
        assertThat(GoogleReviewLinks.writeReviewUrl("ChIJN1t_tDeuEmsRUsoyG83frY4"))
                .isEqualTo("https://search.google.com/local/writereview?placeid=ChIJN1t_tDeuEmsRUsoyG83frY4");
        assertThat(GoogleReviewLinks.writeReviewUrl(" ")).isNull();
        assertThat(GoogleReviewLinks.writeReviewUrl(null)).isNull();
    }

    @Test
    void buildsMapsLinkThatWorksWithoutApiKey() {
        assertThat(GoogleReviewLinks.mapsUrl("ChIJN1t_tDeuEmsRUsoyG83frY4", "Kashmir Cafe"))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=Kashmir+Cafe"
                        + "&query_place_id=ChIJN1t_tDeuEmsRUsoyG83frY4");
        assertThat(GoogleReviewLinks.mapsUrl(null, "Kashmir Cafe")).isNull();
    }
}
