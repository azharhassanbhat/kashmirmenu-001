package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One database aggregate row: payments of one method/provider within one business-local hour.
 * Every number the dashboard shows is derived from these rows, which is why the method, provider,
 * time-series and heatmap totals always reconcile with each other.
 *
 * <p>Boxed types because this is also the target of a JPQL constructor expression.
 */
public record HourlyFact(
        PaymentMethod method,
        PaymentProvider provider,
        Integer year,
        Integer month,
        Integer day,
        Integer hour,
        Long count,
        BigDecimal amount) {

    public LocalDateTime hourStart() {
        return LocalDateTime.of(year, month, day, hour, 0);
    }
}
