package com.kashmirmenu.app.dto.response;

import java.util.List;

/**
 * Everything the dashboard needs to render payment methods without hard-coding them: labels, grouping,
 * providers per method, statuses, staff for the filter and the business time zone. A new payment
 * method added on the server appears here, and so in the filters and charts, with no frontend change.
 */
public record PaymentMetaResponse(
        String currency,
        String timezone,
        List<Method> methods,
        List<Status> statuses,
        List<Option> cardTypes,
        List<Staff> staff) {

    public record Method(
            String key,
            String label,
            boolean digital,
            boolean cash,
            boolean dinerSelectable,
            List<Option> providers) {
    }

    public record Option(String key, String label) {
    }

    public record Status(String key, String label, boolean captured) {
    }

    public record Staff(Long id, String name) {
    }
}
