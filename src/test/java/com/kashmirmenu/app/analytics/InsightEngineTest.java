package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.AnalyticsOverview.Insight;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightEngineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);

    private static AnalyticsOverview overview(PeriodPreset preset, List<HourlyFact> cur, List<HourlyFact> prev) {
        ResolvedPeriod period = preset == PeriodPreset.CUSTOM
                ? PeriodResolver.resolve(preset, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), NOW)
                : PeriodResolver.resolve(preset, null, null, NOW);
        return AnalyticsCalculator.compute(new AnalyticsInput("INR", "Asia/Kolkata", period,
                Granularity.DAILY, cur, List.of(), prev, List.of(), List.of(), 0, 0, null));
    }

    private static HourlyFact cur(PaymentMethod m, PaymentProvider p, int hour, long count, String amount) {
        return new HourlyFact(m, p, 2026, 10, 3, hour, count, new BigDecimal(amount));
    }

    private static HourlyFact prev(PaymentMethod m, long count, String amount) {
        return new HourlyFact(m, null, 2026, 9, 28, 13, count, new BigDecimal(amount));
    }

    private static boolean has(AnalyticsOverview o, String id) {
        return o.insights().stream().anyMatch(i -> i.id().equals(id));
    }

    private static String text(AnalyticsOverview o, String id) {
        return o.insights().stream().filter(i -> i.id().equals(id)).map(Insight::text).findFirst().orElseThrow();
    }

    @Test
    void saysNothingAboutSharesWhenThereIsTooLittleData() {
        // 3 payments is not enough to rank anything.
        List<HourlyFact> f = List.of(cur(PaymentMethod.UPI, PaymentProvider.GOOGLE_PAY, 12, 3, "900.00"));
        AnalyticsOverview o = overview(PeriodPreset.CUSTOM, f, List.of());
        assertFalse(has(o, "top-method"));
        assertFalse(has(o, "upi-app"));
        assertFalse(has(o, "digital-vs-cash"));
    }

    @Test
    void reportsTheShareOfTheLeadingMethodInPlainWords() {
        List<HourlyFact> f = List.of(
                cur(PaymentMethod.UPI, PaymentProvider.PHONEPE, 12, 16, "6400.00"),
                cur(PaymentMethod.CASH, null, 12, 9, "3600.00"));
        AnalyticsOverview o = overview(PeriodPreset.THIS_MONTH, f, List.of());
        assertEquals("UPI accounts for 64% of collected payments this month (₹6,400).", text(o, "top-method"));
    }

    @Test
    void reportsTheLeadingUpiAppAsAShareOfUpiRevenue() {
        List<HourlyFact> f = List.of(
                cur(PaymentMethod.UPI, PaymentProvider.PHONEPE, 12, 8, "4200.00"),
                cur(PaymentMethod.UPI, PaymentProvider.GOOGLE_PAY, 12, 6, "3600.00"),
                cur(PaymentMethod.UPI, PaymentProvider.PAYTM_UPI, 12, 4, "2200.00"));
        AnalyticsOverview o = overview(PeriodPreset.CUSTOM, f, List.of());
        assertEquals("PhonePe contributed 42% of UPI revenue.", text(o, "upi-app"));
    }

    @Test
    void flagsALargeUnknownUpiShareSoStaffCanFixTheData() {
        List<HourlyFact> f = List.of(
                cur(PaymentMethod.UPI, PaymentProvider.GOOGLE_PAY, 12, 4, "1000.00"),
                cur(PaymentMethod.UPI, null, 12, 6, "3000.00"));
        AnalyticsOverview o = overview(PeriodPreset.CUSTOM, f, List.of());
        assertTrue(has(o, "unknown-upi"));
        assertTrue(text(o, "unknown-upi").startsWith("75% of UPI revenue has no app recorded."));
    }

    @Test
    void growthNeedsEnoughPaymentsInBothPeriods() {
        List<HourlyFact> current = List.of(
                cur(PaymentMethod.CARD, null, 12, 6, "1180.00"),
                cur(PaymentMethod.CASH, null, 12, 6, "900.00"));

        List<HourlyFact> enough = List.of(
                prev(PaymentMethod.CARD, 6, "1000.00"),
                prev(PaymentMethod.CASH, 6, "900.00"));
        AnalyticsOverview withBase = overview(PeriodPreset.CUSTOM, current, enough);
        assertEquals("Card collections grew 18% compared with the previous period.", text(withBase, "fastest-growing"));

        // Only 2 payments before: no trend is claimed.
        List<HourlyFact> thin = List.of(prev(PaymentMethod.CARD, 2, "400.00"));
        assertFalse(has(overview(PeriodPreset.CUSTOM, current, thin), "fastest-growing"));
        assertFalse(has(overview(PeriodPreset.CUSTOM, current, List.of()), "fastest-growing"));
    }

    @Test
    void describesTheDigitalShiftOnlyWhenItIsMaterial() {
        List<HourlyFact> current = List.of(
                cur(PaymentMethod.UPI, PaymentProvider.PHONEPE, 12, 8, "7000.00"),
                cur(PaymentMethod.CASH, null, 12, 4, "3000.00"));
        List<HourlyFact> before = List.of(
                new HourlyFact(PaymentMethod.UPI, null, 2026, 9, 28, 13, 5L, new BigDecimal("5000.00")),
                prev(PaymentMethod.CASH, 5, "5000.00"));
        AnalyticsOverview o = overview(PeriodPreset.CUSTOM, current, before);
        assertEquals("Digital payments make up 70% of collections in the selected period, up 20 points from the previous period.",
                text(o, "digital-vs-cash"));
    }

    @Test
    void peakHourNeedsVolumeAndSpread() {
        List<HourlyFact> f = List.of(
                cur(PaymentMethod.CASH, null, 20, 8, "4000.00"),
                cur(PaymentMethod.CASH, null, 13, 4, "1000.00"));
        AnalyticsOverview o = overview(PeriodPreset.CUSTOM, f, List.of());
        assertEquals("Collections peak between 8 PM and 9 PM: ₹4,000 (80% of the total).", text(o, "peak-hour"));

        List<HourlyFact> oneHour = List.of(cur(PaymentMethod.CASH, null, 20, 12, "5000.00"));
        assertFalse(has(overview(PeriodPreset.CUSTOM, oneHour, List.of()), "peak-hour"));
    }

    @Test
    void hourLabelsWrapCorrectly() {
        assertEquals("12 AM", InsightEngine.hourLabel(0));
        assertEquals("12 PM", InsightEngine.hourLabel(12));
        assertEquals("11 PM", InsightEngine.hourLabel(23));
    }
}
