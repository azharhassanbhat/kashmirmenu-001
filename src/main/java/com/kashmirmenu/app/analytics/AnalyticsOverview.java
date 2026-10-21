package com.kashmirmenu.app.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Response of the analytics overview endpoint. All money is exact BigDecimal. */
public record AnalyticsOverview(
        PeriodInfo period,
        Totals totals,
        Totals previousTotals,
        Deltas deltas,
        Sales sales,
        List<MethodRow> methods,
        List<ProviderRow> providers,
        Series series,
        Heatmap heatmap,
        OpenItems open,
        List<Insight> insights,
        List<Definition> definitions) {

    public record PeriodInfo(
            String preset,
            LocalDate from,
            LocalDate to,
            LocalDate previousFrom,
            LocalDate previousTo,
            /** The previous window ends here (exclusive); earlier than a full cycle while the period is in progress. */
            LocalDateTime previousEndExclusive,
            String granularity,
            String currency,
            String timezone) {
    }

    /**
     * @param gross        sum of captured payments (money received), before refunds
     * @param transactions number of captured payment records (a split bill is several)
     * @param uniqueOrders distinct orders those payments belong to
     * @param refunds      refunds issued in the period (cash basis: counted when refunded)
     * @param net          gross minus refunds
     */
    public record Totals(
            BigDecimal gross,
            long transactions,
            long uniqueOrders,
            BigDecimal averageTransaction,
            BigDecimal refunds,
            long refundCount,
            BigDecimal net) {
    }

    /** Change versus the previous period. {@code percent} is null when the previous value was zero. */
    public record Delta(BigDecimal absolute, BigDecimal percent) {
    }

    public record Deltas(Delta gross, Delta net, Delta transactions, Delta averageTransaction, Delta refunds) {
    }

    /**
     * @param grossSales      billed to guests, including tax and service charge
     * @param taxCollected    tax added on top of menu prices
     * @param taxIncluded     tax already inside menu prices
     * @param serviceCharges  service charge billed
     * @param salesExcludingTax grossSales less all tax: what the dishes and service charge earned
     */
    public record Sales(BigDecimal grossSales, long ordersPlaced, BigDecimal taxCollected,
                        BigDecimal taxIncluded, BigDecimal serviceCharges, BigDecimal salesExcludingTax) {
    }

    public record MethodRow(
            String key,
            String label,
            boolean digital,
            BigDecimal gross,
            long transactions,
            BigDecimal shareOfCollected,
            BigDecimal averageTransaction,
            BigDecimal refunds,
            long refundCount,
            BigDecimal net,
            BigDecimal previousGross,
            long previousTransactions,
            BigDecimal deltaPercent,
            long pendingCount,
            long failedCount,
            long cancelledCount) {
    }

    /** A UPI app or wallet. {@code key} is UNKNOWN_UPI / UNKNOWN_WALLET when no provider was recorded. */
    public record ProviderRow(
            String key,
            String label,
            String method,
            String methodLabel,
            boolean known,
            BigDecimal gross,
            long transactions,
            BigDecimal shareOfMethod,
            BigDecimal shareOfCollected,
            BigDecimal averageTransaction,
            BigDecimal refunds,
            long refundCount,
            BigDecimal previousGross,
            BigDecimal deltaPercent,
            long pendingCount,
            long failedCount,
            long cancelledCount) {
    }

    public record Series(
            String granularity,
            List<LocalDateTime> buckets,
            List<SeriesRow> rows) {
    }

    public record SeriesRow(String key, String label, List<BigDecimal> amounts, List<Long> counts) {
    }

    /** Day index 0 = Monday ... 6 = Sunday; hour 0..23, business-local. */
    public record Heatmap(
            List<List<BigDecimal>> amounts,
            List<List<Long>> counts,
            List<BigDecimal> hourlyAmounts,
            List<Long> hourlyCounts) {
    }

    public record OpenItems(
            long pendingCount,
            BigDecimal pendingAmount,
            long failedCount,
            BigDecimal failedAmount,
            long cancelledCount,
            BigDecimal cancelledAmount) {
    }

    /** {@code tone} is one of positive, negative, neutral, info. */
    public record Insight(String id, String tone, String text) {
    }

    public record Definition(String key, String label, String formula) {
    }
}
