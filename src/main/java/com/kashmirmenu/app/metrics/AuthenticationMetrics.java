package com.kashmirmenu.app.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The one custom metric in the application: a count of failed sign-in attempts.
 *
 * <p>Everything else the dashboards need is already exported by Micrometer. Authorization failures,
 * for instance, are visible as {@code http_server_requests_seconds_count{status="403"}}, so a second
 * counter for them would only duplicate an existing signal.
 *
 * <p>No identifier of any kind is recorded here - no email, no user id, no IP, no request id. The
 * audit log already holds the detail needed to investigate an individual attempt; this exists purely
 * to make a rate graphable. The {@code reason} tag is drawn from a fixed, bounded set and never from
 * user input, so it cannot inflate cardinality.
 */
@Component
public class AuthenticationMetrics {

    /** Credentials did not match, or the account does not exist. The two are not distinguished. */
    public static final String REASON_BAD_CREDENTIALS = "bad_credentials";

    /** A platform-owner claim was refused: wrong, expired, or already-used token. */
    public static final String REASON_CLAIM_REFUSED = "claim_refused";

    private final Counter badCredentials;
    private final Counter claimRefused;

    public AuthenticationMetrics(MeterRegistry registry) {
        this.badCredentials = counter(registry, REASON_BAD_CREDENTIALS);
        this.claimRefused = counter(registry, REASON_CLAIM_REFUSED);
    }

    private static Counter counter(MeterRegistry registry, String reason) {
        return Counter.builder("app.authentication.failures")
                .description("Failed authentication attempts, with no identifying detail")
                .tag("reason", reason)
                .register(registry);
    }

    public void recordBadCredentials() {
        badCredentials.increment();
    }

    public void recordClaimRefused() {
        claimRefused.increment();
    }
}
