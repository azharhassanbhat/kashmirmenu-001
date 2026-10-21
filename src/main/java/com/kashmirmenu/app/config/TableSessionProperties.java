package com.kashmirmenu.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.table-session")
public class TableSessionProperties {

    /**
     * Most guests one table visit will admit. Joining needs no login, so without a ceiling anyone
     * holding the QR code could create guest rows without limit.
     */
    private int maxDiners = 30;

    /**
     * How long a table visit may go with no guest doing anything before it counts as idle. A guest's
     * own row records when they last acted, so a visit's activity is the newest of those.
     */
    private Duration idleTimeout = Duration.ofHours(3);

    /** How often idle visits are looked for. Read by the scheduler's placeholder as well as bound here. */
    private Duration expiryCheckInterval = Duration.ofMinutes(10);

    /** Switch automatic closing off entirely; staff can still close tables. */
    private boolean expiryEnabled = true;

    /** How far back "recently closed" tables are listed, so one closed by mistake can be reopened. */
    private Duration recentlyClosedWindow = Duration.ofHours(24);
}
