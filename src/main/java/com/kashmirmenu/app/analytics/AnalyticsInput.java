package com.kashmirmenu.app.analytics;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the calculator needs, already fetched by the database aggregation queries.
 *
 * @param sales gross sales of orders placed in the period; null when payment filters are active,
 *              because orders cannot be filtered by payment method
 */
public record AnalyticsInput(
        String currency,
        String timezone,
        ResolvedPeriod period,
        Granularity granularity,
        List<HourlyFact> collected,
        List<HourlyFact> refunds,
        List<HourlyFact> previousCollected,
        List<HourlyFact> previousRefunds,
        List<OpenFact> open,
        long uniqueOrders,
        long previousUniqueOrders,
        Sales sales) {

    /**
     * Orders billed in the period. {@code grossSales} is the amount billed to guests, so it includes
     * any tax and service charge. The other figures say how much of it was which, so that tax, which
     * is owed onward and is not the restaurant's income, can be separated from sales.
     *
     * @param taxCollected tax added on top of menu prices (exclusive pricing)
     * @param taxIncluded  tax already inside menu prices, for restaurants whose prices include it
     * @param serviceCharges service charge billed
     */
    public record Sales(BigDecimal grossSales, long ordersPlaced, BigDecimal taxCollected,
                        BigDecimal taxIncluded, BigDecimal serviceCharges) {

        /** Without any tax information: nothing is assumed to be tax. */
        public Sales(BigDecimal grossSales, long ordersPlaced) {
            this(grossSales, ordersPlaced, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
