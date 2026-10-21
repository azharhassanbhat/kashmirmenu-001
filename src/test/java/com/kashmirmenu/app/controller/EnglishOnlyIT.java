package com.kashmirmenu.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashmirmenu.app.TestProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The app is English only. Hibernate Validator ships translations of its default messages ("must not be
 * blank") and would use them for a caller whose browser asks for another language, so the server pins its
 * locale. This proves a request asking for German, Hindi, Arabic, Japanese or French still gets English.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class EnglishOnlyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** RegisterRequest has constraints with no message of their own, so the DEFAULT wording is what is returned. */
    private String messageFor(String acceptLanguage) throws Exception {
        var request = post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"email\":\"not-an-email\",\"password\":\"short\"}");
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        // The server lists the violations in no fixed order, so compare them sorted.
        java.util.List<String> messages = new java.util.ArrayList<>();
        for (JsonNode e : json.get("errors")) {
            messages.add(e.get("message").asText());
        }
        java.util.Collections.sort(messages);
        StringBuilder all = new StringBuilder();
        for (String message : messages) {
            all.append(message).append(" | ");
        }
        return all.toString();
    }

    @Test
    void validationMessagesAreEnglishByDefault() throws Exception {
        String m = messageFor(null);
        assertTrue(m.contains("must not be blank"), m);
        assertTrue(m.contains("must be a well-formed email address"), m);
        assertTrue(m.contains("size must be between 8 and 100"), m);
    }

    @Test
    void askingForAnotherLanguageStillGetsEnglish() throws Exception {
        String english = messageFor(null);
        for (String language : new String[]{"de", "de-DE,de;q=0.9", "hi", "hi-IN", "ar", "ja", "fr-FR", "zh-CN", "ur", "es"}) {
            assertEquals(english, messageFor(language), "Accept-Language: " + language);
        }
    }

    @Test
    void aNonsenseLanguageHeaderIsHarmless() throws Exception {
        assertEquals(messageFor(null), messageFor("*;q=0, xx-YY, ;;;"));
    }
}
