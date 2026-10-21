package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.AnalyticsOverview.MethodRow;
import com.kashmirmenu.app.analytics.AnalyticsOverview.ProviderRow;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsCalculatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final ResolvedPeriod WEEK = PeriodResolver.resolve(
            PeriodPreset.CUSTOM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), NOW);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** A fact on Saturday 3 Oct 2026 (inside WEEK) at the given hour. */
    private static HourlyFact fact(PaymentMethod m, PaymentProvider p, int hour, long count, String amount) {
        return new HourlyFact(m, p, 2026, 10, 3, hour, count, d(amount));
    }

    private static HourlyFact prevFact(PaymentMethod m, PaymentProvider p, long count, String amount) {
        return new HourlyFact(m, p, 2026, 9, 28, 13, count, d(amount));
    }

    private static AnalyticsInput input(List<HourlyFact> cur, List<HourlyFact> refunds,
                                        List<HourlyFact> prev, List<OpenFact> open) {
        return new AnalyticsInput("INR", "Asia/Kolkata", WEEK, Granularity.DAILY,
                cur, refunds, prev, List.of(), open, 0, 0, null);
    }

    /** The brief's illustrative breakdown: 1,85,450 = UPI 1,11,270 + Cash 46,363 + Card/Wallet 27,817. */
    private static List<HourlyFact> briefScenario() {
        List<HourlyFact> f = new ArrayList<>();
        f.add(fact(PaymentMethod.UPI, PaymentProvider.GOOGLE_PAY, 13, 40, "50072.00"));
        f.add(fact(PaymentMethod.UPI, PaymentProvider.PHONEPE, 13, 30, "38945.00"));
        f.add(fact(PaymentMethod.UPI, PaymentProvider.PAYTM_UPI, 20, 12, "16691.00"));
        f.add(fact(PaymentMethod.UPI, PaymentProvider.OTHER_UPI, 20, 5, "5562.00"));
        f.add(fact(PaymentMethod.CASH, null, 13, 50, "46363.00"));
        f.add(fact(PaymentMethod.CARD, null, 21, 9, "20000.00"));
        f.add(fact(PaymentMethod.WALLET, PaymentProvider.PAYTM_WALLET, 21, 4, "7817.00"));
        return f;
    }

    private static MethodRow method(AnalyticsOverview o, String key) {
        return o.methods().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    private static ProviderRow provider(AnalyticsOverview o, String key) {
        return o.providers().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void totalsAndSharesMatchTheBriefsExample() {
        AnalyticsOverview o = AnalyticsCalculator.compute(input(briefScenario(), List.of(), List.of(), List.of()));

        assertEquals(d("185450.00"), o.totals().gross());
        assertEquals(d("111270.00"), method(o, "UPI").gross());
        assertEquals(d("46363.00"), method(o, "CASH").gross());
        assertEquals(d("60.0"), method(o, "UPI").shareOfCollected());
        assertEquals(d("25.0"), method(o, "CASH").shareOfCollected());

        // Within UPI: Google Pay 45%, PhonePe 35%, Paytm UPI 15%, Other 5%.
        assertEquals(d("45.0"), provider(o, "GOOGLE_PAY").shareOfMethod());
        assertEquals(d("35.0"), provider(o, "PHONEPE").shareOfMethod());
        assertEquals(d("15.0"), provider(o, "PAYTM_UPI").shareOfMethod());
        assertEquals(d("5.0"), provider(o, "OTHER_UPI").shareOfMethod());
    }

    @Test
    void everyViewReconcilesWithTheTotal() {
        AnalyticsOverview o = AnalyticsCalculator.compute(input(briefScenario(), List.of(), List.of(), List.of()));
        BigDecimal total = o.totals().gross();

        BigDecimal byMethod = o.methods().stream().map(MethodRow::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(total, byMethod);

        // Providers add up to their parent method.
        for (String parent : List.of("UPI", "WALLET")) {
            BigDecimal sum = o.providers().stream().filter(p -> p.method().equals(parent))
                    .map(ProviderRow::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(method(o, parent).gross(), sum);
            long tx = o.providers().stream().filter(p -> p.method().equals(parent))
                    .mapToLong(ProviderRow::transactions).sum();
            assertEquals(method(o, parent).transactions(), tx);
        }

        BigDecimal bySeries = o.series().rows().stream().flatMap(r -> r.amounts().stream())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(total, bySeries);

        BigDecimal byHeatmap = o.heatmap().amounts().stream().flatMap(List::stream)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(total, byHeatmap);

        long txFromMethods = o.methods().stream().mapToLong(MethodRow::transactions).sum();
        assertEquals(o.totals().transactions(), txFromMethods);
    }

    @Test
    void paytmUpiAndPaytmWalletAreSeparate() {
        AnalyticsOverview o = AnalyticsCalculator.compute(input(briefScenario(), List.of(), List.of(), List.of()));
        assertEquals(d("16691.00"), provider(o, "PAYTM_UPI").gross());
        assertEquals("UPI", provider(o, "PAYTM_UPI").method());
        assertEquals(d("7817.00"), provider(o, "PAYTM_WALLET").gross());
        assertEquals("WALLET", provider(o, "PAYTM_WALLET").method());
    }

    @Test
    void aMissingProviderStaysUnknown_andIsNeverMergedIntoAnotherApp() {
        List<HourlyFact> f = new ArrayList<>();
        f.add(fact(PaymentMethod.UPI, PaymentProvider.GOOGLE_PAY, 12, 3, "300.00"));
        f.add(fact(PaymentMethod.UPI, null, 12, 7, "700.00")); // historical rows: provider never recorded

        AnalyticsOverview o = AnalyticsCalculator.compute(input(f, List.of(), List.of(), List.of()));

        ProviderRow unknown = provider(o, AnalyticsCalculator.UNKNOWN_UPI);
        assertFalse(unknown.known());
        assertEquals(d("700.00"), unknown.gross());
        assertEquals(7, unknown.transactions());
        assertEquals(d("300.00"), provider(o, "GOOGLE_PAY").gross());
        // Unknown is listed last among UPI providers.
        List<ProviderRow> upi = o.providers().stream().filter(p -> p.method().equals("UPI")).toList();
        assertEquals(AnalyticsCalculator.UNKNOWN_UPI, upi.get(upi.size() - 1).key());
    }

    @Test
    void pendingFailedAndCancelledPaymentsAreNeverRevenue() {
        List<OpenFact> open = List.of(
                new OpenFact(PaymentStatus.PENDING, PaymentMethod.UPI, null, 2L, d("500.00")),
                new OpenFact(PaymentStatus.FAILED, PaymentMethod.CARD, null, 1L, d("900.00")),
                new OpenFact(PaymentStatus.CANCELLED, PaymentMethod.CASH, null, 1L, d("120.00")));
        List<HourlyFact> collected = List.of(fact(PaymentMethod.CASH, null, 12, 1, "200.00"));

        AnalyticsOverview o = AnalyticsCalculator.compute(input(collected, List.of(), List.of(), open));

        assertEquals(d("200.00"), o.totals().gross());
        assertEquals(2, o.open().pendingCount());
        assertEquals(d("500.00"), o.open().pendingAmount());
        assertEquals(d("900.00"), o.open().failedAmount());
        assertEquals(1, method(o, "CARD").failedCount());
        assertEquals(d("0.00"), method(o, "CARD").gross()); // appears because it had a failure, earns nothing
        assertEquals(2, method(o, "UPI").pendingCount());
    }

    @Test
    void refundsReduceNetButNotGross() {
        List<HourlyFact> collected = List.of(fact(PaymentMethod.CASH, null, 12, 4, "1000.00"));
        List<HourlyFact> refunds = List.of(fact(PaymentMethod.CASH, null, 15, 1, "250.00"));

        AnalyticsOverview o = AnalyticsCalculator.compute(input(collected, refunds, List.of(), List.of()));

        assertEquals(d("1000.00"), o.totals().gross());
        assertEquals(d("250.00"), o.totals().refunds());
        assertEquals(1, o.totals().refundCount());
        assertEquals(d("750.00"), o.totals().net());
        assertEquals(d("750.00"), method(o, "CASH").net());
    }

    @Test
    void comparisonUsesThePreviousPeriod_andHasNoPercentWithoutABase() {
        List<HourlyFact> cur = List.of(
                fact(PaymentMethod.CARD, null, 12, 6, "1180.00"),
                fact(PaymentMethod.CASH, null, 12, 5, "500.00"));
        List<HourlyFact> prev = List.of(prevFact(PaymentMethod.CARD, null, 6, "1000.00"));

        AnalyticsOverview o = AnalyticsCalculator.compute(input(cur, List.of(), prev, List.of()));

        assertEquals(d("18.0"), method(o, "CARD").deltaPercent());
        assertEquals(d("1000.00"), method(o, "CARD").previousGross());
        assertNull(method(o, "CASH").deltaPercent()); // cash had no previous collections
        assertEquals(d("1680.00"), o.totals().gross());
        assertEquals(d("1000.00"), o.previousTotals().gross());
        assertEquals(d("680.00"), o.deltas().gross().absolute());
        assertEquals(d("68.0"), o.deltas().gross().percent());
    }

    @Test
    void aMethodActiveOnlyInThePreviousPeriodIsStillShownWithItsDrop() {
        List<HourlyFact> cur = List.of(fact(PaymentMethod.CASH, null, 12, 5, "500.00"));
        List<HourlyFact> prev = List.of(prevFact(PaymentMethod.CARD, null, 4, "800.00"));

        AnalyticsOverview o = AnalyticsCalculator.compute(input(cur, List.of(), prev, List.of()));

        MethodRow card = method(o, "CARD");
        assertEquals(d("0.00"), card.gross());
        assertEquals(d("-100.0"), card.deltaPercent());
    }

    @Test
    void timeSeriesIsContinuous_andHeatmapUsesBusinessWeekdayAndHour() {
        List<HourlyFact> f = List.of(
                fact(PaymentMethod.CASH, null, 13, 2, "100.00"),
                fact(PaymentMethod.CASH, null, 21, 1, "50.00"));

        AnalyticsOverview o = AnalyticsCalculator.compute(input(f, List.of(), List.of(), List.of()));

        assertEquals(7, o.series().buckets().size()); // 1..7 Oct, empty days included
        var cash = o.series().rows().get(0);
        assertEquals("CASH", cash.key());
        assertEquals(d("150.00"), cash.amounts().get(2)); // 3 Oct is the third bucket
        assertEquals(d("0.00"), cash.amounts().get(0));

        // 3 Oct 2026 is a Saturday -> index 5.
        assertEquals(d("100.00"), o.heatmap().amounts().get(5).get(13));
        assertEquals(2L, (long) o.heatmap().counts().get(5).get(13));
        assertEquals(d("50.00"), o.heatmap().hourlyAmounts().get(21));
    }

    @Test
    void averageTransactionIsGrossOverTransactions() {
        List<HourlyFact> f = List.of(fact(PaymentMethod.CARD, null, 12, 3, "1000.00"));
        AnalyticsOverview o = AnalyticsCalculator.compute(input(f, List.of(), List.of(), List.of()));
        assertEquals(d("333.33"), o.totals().averageTransaction());
        assertEquals(d("333.33"), method(o, "CARD").averageTransaction());
    }

    @Test
    void salesAreCarriedThroughWhenSupplied_andOmittedOtherwise() {
        var withSales = new AnalyticsInput("INR", "Asia/Kolkata", WEEK, Granularity.DAILY,
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0,
                new AnalyticsInput.Sales(d("9999.5"), 12));
        AnalyticsOverview o = AnalyticsCalculator.compute(withSales);
        assertNotNull(o.sales());
        assertEquals(d("9999.50"), o.sales().grossSales());
        assertNull(AnalyticsCalculator.compute(input(List.of(), List.of(), List.of(), List.of())).sales());
    }

    @Test
    void emptyDataProducesZeroesNotErrors() {
        AnalyticsOverview o = AnalyticsCalculator.compute(input(List.of(), List.of(), List.of(), List.of()));
        assertEquals(d("0.00"), o.totals().gross());
        assertEquals(0, o.methods().size());
        assertEquals(0, o.providers().size());
        assertTrue(o.insights().isEmpty());
        assertEquals(24, o.heatmap().hourlyAmounts().size());
    }
}
