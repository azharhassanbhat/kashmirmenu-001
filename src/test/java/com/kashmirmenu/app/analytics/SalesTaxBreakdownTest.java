package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.AnalyticsOverview.Sales;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Gross sales is what guests were billed, tax and service charge included. Tax is owed onward and is not
 * income, so the report also says how much of the gross was tax and what the sales were without it.
 */
class SalesTaxBreakdownTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final ResolvedPeriod WEEK = PeriodResolver.resolve(
            PeriodPreset.CUSTOM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), NOW);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Sales salesOf(AnalyticsInput.Sales in) {
        return AnalyticsCalculator.salesOf(in);
    }

    @Test
    void taxAddedOnTopIsSeparatedFromSales() {
        // 1000 of food + 100 service charge + 55 tax = 1155 billed
        Sales s = salesOf(new AnalyticsInput.Sales(d("1155"), 3, d("55"), d("0"), d("100")));
        assertEquals(d("1155.00"), s.grossSales());
        assertEquals(d("55.00"), s.taxCollected());
        assertEquals(d("1100.00"), s.salesExcludingTax());
    }

    @Test
    void theServiceChargeStaysInSalesBecauseTheRestaurantBilledIt() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("1155"), 3, d("55"), d("0"), d("100")));
        assertEquals(d("100.00"), s.serviceCharges());
        assertEquals(d("1100.00"), s.salesExcludingTax());   // food 1000 + service charge 100
    }

    @Test
    void taxAlreadyInsideMenuPricesIsAlsoTakenOut() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("1050"), 1, d("0"), d("50"), d("0")));
        assertEquals(d("1050.00"), s.grossSales());
        assertEquals(d("50.00"), s.taxIncluded());
        assertEquals(d("1000.00"), s.salesExcludingTax());
    }

    @Test
    void bothKindsOfTaxAreTakenOutTogether() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("2000"), 5, d("55"), d("50"), d("0")));
        assertEquals(d("1895.00"), s.salesExcludingTax());
    }

    @Test
    void withNoTaxInformationNothingIsAssumedToBeTaxAsBefore() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("9999.5"), 12));
        assertEquals(d("9999.50"), s.grossSales());
        assertEquals(d("0.00"), s.taxCollected());
        assertEquals(d("0.00"), s.taxIncluded());
        assertEquals(d("9999.50"), s.salesExcludingTax());
        assertEquals(12, s.ordersPlaced());
    }

    @Test
    void salesExcludingTaxIsNeverNegativeEvenIfTheFiguresDisagree() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("100"), 1, d("150"), d("0"), d("0")));
        assertEquals(d("0.00"), s.salesExcludingTax());
    }

    @Test
    void everyFigureIsTwoDecimalPlaces() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("10.005"), 1, d("0.1"), d("0"), d("0")));
        assertEquals(2, s.grossSales().scale());
        assertEquals(2, s.taxCollected().scale());
        assertEquals(2, s.salesExcludingTax().scale());
    }

    @Test
    void missingFiguresCountAsZeroNotAsAnError() {
        Sales s = salesOf(new AnalyticsInput.Sales(d("500"), 2, null, null, null));
        assertEquals(d("0.00"), s.taxCollected());
        assertEquals(d("0.00"), s.serviceCharges());
        assertEquals(d("500.00"), s.salesExcludingTax());
    }

    @Test
    void theBreakdownReachesTheOverviewTheOwnerSees() {
        var input = new AnalyticsInput("INR", "Asia/Kolkata", WEEK, Granularity.DAILY,
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0,
                new AnalyticsInput.Sales(d("1155"), 3, d("55"), d("0"), d("100")));
        Sales s = AnalyticsCalculator.compute(input).sales();
        assertEquals(d("55.00"), s.taxCollected());
        assertEquals(d("1100.00"), s.salesExcludingTax());
    }

    @Test
    void salesAreStillOmittedWhenNoneWereSupplied() {
        var input = new AnalyticsInput("INR", "Asia/Kolkata", WEEK, Granularity.DAILY,
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, null);
        assertNull(AnalyticsCalculator.compute(input).sales());
    }
}
