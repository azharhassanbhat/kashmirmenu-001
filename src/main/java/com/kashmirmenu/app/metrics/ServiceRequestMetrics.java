package com.kashmirmenu.app.metrics;

import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Counters and a response-time timer for service requests. Tags are the request type and status
 * only - never a table, guest, note or restaurant name, so nothing personal reaches Prometheus.
 */
@Component
public class ServiceRequestMetrics {

    private final MeterRegistry registry;

    public ServiceRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void created(ServiceRequestType type) {
        Counter.builder("app.service_requests.created")
                .description("Service requests raised by guests")
                .tag("type", type.name())
                .register(registry).increment();
    }

    public void duplicateIgnored(ServiceRequestType type) {
        Counter.builder("app.service_requests.duplicates")
                .description("Repeat taps answered with the existing open request")
                .tag("type", type.name())
                .register(registry).increment();
    }

    public void transitioned(ServiceRequestStatus to) {
        Counter.builder("app.service_requests.transitions")
                .description("Status changes made by staff or guests")
                .tag("to", to.name())
                .register(registry).increment();
    }

    /** Time from submission to a member of staff first acknowledging it. */
    public void acknowledgedAfter(Duration wait) {
        Timer.builder("app.service_requests.response_time")
                .description("Submission to first acknowledgement")
                .publishPercentileHistogram()
                .register(registry).record(wait);
    }
}
