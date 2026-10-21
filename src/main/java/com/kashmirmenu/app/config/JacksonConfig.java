package com.kashmirmenu.app.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sends every timestamp with the restaurant's UTC offset, e.g. {@code 2026-10-13T14:35:00+05:30}.
 *
 * <p>Timestamps are stored as zone-less {@code LocalDateTime} values in the restaurant's business
 * zone (see {@link BusinessTime}). Serialised bare, as they were, the receiving browser has no way to
 * know which zone they belong to and assumes its own: a diner or a kitchen tablet whose device zone
 * differs from the server's read every time as wrong by exactly that difference, which is why a
 * just-placed order could appear as "5h 30m ago".
 *
 * <p>Adding the offset makes the value an unambiguous instant, so {@code new Date(value)} in the
 * browser is correct from any device, in any zone. Reading is unaffected: Jackson still accepts a
 * bare {@code 2026-01-31T10:00:00} in request bodies.
 */
@Configuration
@RequiredArgsConstructor
public class JacksonConfig {

    private final BusinessTime businessTime;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer businessZoneTimestamps() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDateTime.class, new BusinessOffsetSerializer(businessTime));
        return builder -> builder.modulesToInstall(module);
    }

    @RequiredArgsConstructor
    static final class BusinessOffsetSerializer extends JsonSerializer<LocalDateTime> {

        private final BusinessTime businessTime;

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {

            // The offset is resolved for the value's own date, so timestamps either side of a
            // daylight-saving change keep the offset that was actually in force.
            gen.writeString(value.atZone(businessTime.zone())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }
}
