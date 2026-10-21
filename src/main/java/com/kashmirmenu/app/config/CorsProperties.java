package com.kashmirmenu.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Origins allowed to call the API / connect to the websocket.
     * Must be explicitly configured per environment; left empty by
     * default so prod fails closed instead of falling back to "*".
     */
    private List<String> allowedOrigins = List.of();
}