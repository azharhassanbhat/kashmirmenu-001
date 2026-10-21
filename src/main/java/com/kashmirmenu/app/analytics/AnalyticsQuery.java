package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentStatus;

import java.time.LocalDate;
import java.util.List;

/** The dashboard's filter state as sent by the client. Null / empty lists mean "no restriction". */
public record AnalyticsQuery(
        PeriodPreset preset,
        LocalDate from,
        LocalDate to,
        Granularity granularity,
        List<PaymentMethod> methods,
        List<String> providers,
        List<PaymentStatus> statuses,
        Long staffId) {
}
