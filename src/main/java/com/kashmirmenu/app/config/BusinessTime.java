package com.kashmirmenu.app.config;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The restaurant's business clock. All stored timestamps are zone-less values in the server's zone and
 * the database session uses the same zone, so day and hour boundaries for analytics are the business's.
 * Configure it with the TZ environment variable (docker-compose sets it for both the app and MySQL
 * from APP_TIMEZONE, default Asia/Kolkata); keep the two equal.
 */
@Component
public class BusinessTime {

    public ZoneId zone() {
        return ZoneId.systemDefault();
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone());
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }
}
