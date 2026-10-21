package com.kashmirmenu.app.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the table-ordering flow, so operators can see lock contention and floor activity without
 * reading logs.
 *
 * <p>Like AuthenticationMetrics, nothing identifying is ever recorded: no guest name, token, table, order
 * or restaurant. Every tag value comes from a small fixed set chosen in code, never from user input, so
 * cardinality cannot grow.
 */
@Component
public class FloorMetrics {

    private final MeterRegistry registry;

    public FloorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A request hit a lock conflict and was run again. A few are normal under load. */
    public void lockRetried(String operation) {
        registry.counter("app.lock.retries", "operation", operation).increment();
    }

    /** A request hit lock conflicts on every attempt and the guest or staff member was told to try again. */
    public void lockGaveUp(String operation) {
        registry.counter("app.lock.failures", "operation", operation).increment();
    }

    /** A guest started paying. {@code kind} is SEPARATE or COMBINED. */
    public void settlementStarted(String kind) {
        registry.counter("app.settlements.started", "kind", kind).increment();
    }

    /** A table was closed. {@code reason} is STAFF or IDLE_EXPIRED. */
    public void tableClosed(String reason) {
        registry.counter("app.table_sessions.closed", "reason", reason).increment();
    }
}
