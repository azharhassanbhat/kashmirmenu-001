package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.kashmirmenu.app.analytics.AnalyticsOverview.*;

/**
 * Turns the aggregated database rows into the dashboard response. Pure and deterministic: no database,
 * no clock, no floating point. Every figure is a sum over the same {@link HourlyFact} rows, so
 * providers add up to their method, methods add up to the total, and the time series and heatmap add up
 * to the same total.
 */
public final class AnalyticsCalculator {

    public static final String UNKNOWN_UPI = "UNKNOWN_UPI";
    public static final String UNKNOWN_WALLET = "UNKNOWN_WALLET";

    private AnalyticsCalculator() {
    }

    private static final class Acc {
        BigDecimal amount = BigDecimal.ZERO;
        long count;

        void add(BigDecimal a, long c) {
            if (a != null) {
                amount = amount.add(a);
            }
            count += c;
        }
    }

    private record ProviderKey(PaymentMethod method, PaymentProvider provider) {
    }

    /** Collected or refunded rows folded into totals, per method, per provider. */
    private static final class Folded {
        final Acc total = new Acc();
        final Map<PaymentMethod, Acc> byMethod = new EnumMap<>(PaymentMethod.class);
        final Map<ProviderKey, Acc> byProvider = new HashMap<>();

        Folded(List<HourlyFact> facts) {
            for (HourlyFact f : facts) {
                long c = f.count() == null ? 0 : f.count();
                total.add(f.amount(), c);
                byMethod.computeIfAbsent(f.method(), k -> new Acc()).add(f.amount(), c);
                if (f.method().hasProviders()) {
                    byProvider.computeIfAbsent(new ProviderKey(f.method(), f.provider()), k -> new Acc())
                            .add(f.amount(), c);
                }
            }
        }

        Acc method(PaymentMethod m) {
            return byMethod.getOrDefault(m, new Acc());
        }

        Acc provider(PaymentMethod m, PaymentProvider p) {
            return byProvider.getOrDefault(new ProviderKey(m, p), new Acc());
        }
    }

    /** Non-captured rows: counts per status, per method and per provider. */
    private static final class OpenFolded {
        final Map<PaymentStatus, Acc> byStatus = new EnumMap<>(PaymentStatus.class);
        final Map<PaymentMethod, Map<PaymentStatus, Acc>> byMethod = new EnumMap<>(PaymentMethod.class);
        final Map<ProviderKey, Map<PaymentStatus, Acc>> byProvider = new HashMap<>();

        OpenFolded(List<OpenFact> facts) {
            for (OpenFact f : facts) {
                long c = f.count() == null ? 0 : f.count();
                byStatus.computeIfAbsent(f.status(), k -> new Acc()).add(f.amount(), c);
                byMethod.computeIfAbsent(f.method(), k -> new EnumMap<>(PaymentStatus.class))
                        .computeIfAbsent(f.status(), k -> new Acc()).add(f.amount(), c);
                if (f.method().hasProviders()) {
                    byProvider.computeIfAbsent(new ProviderKey(f.method(), f.provider()),
                                    k -> new EnumMap<>(PaymentStatus.class))
                            .computeIfAbsent(f.status(), k -> new Acc()).add(f.amount(), c);
                }
            }
        }

        long count(PaymentStatus s) {
            Acc a = byStatus.get(s);
            return a == null ? 0 : a.count;
        }

        BigDecimal amount(PaymentStatus s) {
            Acc a = byStatus.get(s);
            return a == null ? BigDecimal.ZERO : a.amount;
        }

        static long countOf(Map<PaymentStatus, Acc> m, PaymentStatus s) {
            if (m == null) {
                return 0;
            }
            Acc a = m.get(s);
            return a == null ? 0 : a.count;
        }
    }

    public static AnalyticsOverview compute(AnalyticsInput in) {

        Folded cur = new Folded(in.collected());
        Folded prev = new Folded(in.previousCollected());
        Folded curRefunds = new Folded(in.refunds());
        Folded prevRefunds = new Folded(in.previousRefunds());
        OpenFolded open = new OpenFolded(in.open());

        Totals totals = totals(cur.total, curRefunds.total, in.uniqueOrders());
        Totals previousTotals = totals(prev.total, prevRefunds.total, in.previousUniqueOrders());

        Deltas deltas = new Deltas(
                MoneyMath.delta(totals.gross(), previousTotals.gross()),
                MoneyMath.delta(totals.net(), previousTotals.net()),
                MoneyMath.delta(BigDecimal.valueOf(totals.transactions()),
                        BigDecimal.valueOf(previousTotals.transactions())),
                MoneyMath.delta(totals.averageTransaction(), previousTotals.averageTransaction()),
                MoneyMath.delta(totals.refunds(), previousTotals.refunds()));

        List<MethodRow> methods = methodRows(cur, prev, curRefunds, open, totals);
        List<ProviderRow> providers = providerRows(cur, prev, curRefunds, open, totals);

        List<LocalDateTime> buckets = in.granularity().buckets(in.period().current());
        Series series = series(in, buckets);
        Heatmap heatmap = heatmap(in.collected());

        OpenItems openItems = new OpenItems(
                open.count(PaymentStatus.PENDING), MoneyMath.money(open.amount(PaymentStatus.PENDING)),
                open.count(PaymentStatus.FAILED), MoneyMath.money(open.amount(PaymentStatus.FAILED)),
                open.count(PaymentStatus.CANCELLED), MoneyMath.money(open.amount(PaymentStatus.CANCELLED)));

        List<Insight> insights = InsightEngine.generate(
                in.period().preset(), in.currency(), totals, previousTotals, methods, providers,
                heatmap.hourlyAmounts(), heatmap.hourlyCounts(), openItems);

        DateRange c = in.period().current();
        DateRange p = in.period().previous();
        PeriodInfo info = new PeriodInfo(
                in.period().preset().name(),
                c.firstDay(), c.lastDay(),
                p.firstDay(), p.lastDay(), p.endExclusive(),
                in.granularity().name(), in.currency(), in.timezone());

        Sales sales = in.sales() == null ? null : salesOf(in.sales());

        return new AnalyticsOverview(info, totals, previousTotals, deltas, sales, methods, providers,
                series, heatmap, openItems, insights, Definitions.ALL);
    }

    private static Totals totals(Acc collected, Acc refunds, long uniqueOrders) {
        BigDecimal gross = MoneyMath.money(collected.amount);
        BigDecimal refunded = MoneyMath.money(refunds.amount);
        return new Totals(
                gross,
                collected.count,
                uniqueOrders,
                MoneyMath.average(gross, collected.count),
                refunded,
                refunds.count,
                gross.subtract(refunded));
    }

    private static List<MethodRow> methodRows(
            Folded cur, Folded prev, Folded refunds, OpenFolded open, Totals totals) {

        List<MethodRow> rows = new ArrayList<>();
        for (PaymentMethod m : PaymentMethod.values()) {
            Acc c = cur.method(m);
            Acc p = prev.method(m);
            Acc r = refunds.method(m);
            Map<PaymentStatus, Acc> o = open.byMethod.get(m);

            boolean active = c.count > 0 || p.count > 0 || r.count > 0 || o != null;
            if (!active) {
                continue;
            }
            BigDecimal gross = MoneyMath.money(c.amount);
            BigDecimal refunded = MoneyMath.money(r.amount);
            rows.add(new MethodRow(
                    m.name(),
                    m.label(),
                    m.isDigital(),
                    gross,
                    c.count,
                    MoneyMath.percentOf(gross, totals.gross()),
                    MoneyMath.average(gross, c.count),
                    refunded,
                    r.count,
                    gross.subtract(refunded),
                    MoneyMath.money(p.amount),
                    p.count,
                    MoneyMath.percentChange(gross, MoneyMath.money(p.amount)),
                    OpenFolded.countOf(o, PaymentStatus.PENDING),
                    OpenFolded.countOf(o, PaymentStatus.FAILED),
                    OpenFolded.countOf(o, PaymentStatus.CANCELLED)));
        }
        // Biggest earner first; ties keep the declared method order.
        rows.sort(Comparator.comparing(MethodRow::gross).reversed());
        return rows;
    }

    private static List<ProviderRow> providerRows(
            Folded cur, Folded prev, Folded refunds, OpenFolded open, Totals totals) {

        List<ProviderRow> rows = new ArrayList<>();
        for (PaymentMethod m : PaymentMethod.values()) {
            if (!m.hasProviders()) {
                continue;
            }
            Acc methodAcc = cur.method(m);
            boolean methodActive = methodAcc.count > 0 || prev.method(m).count > 0
                    || refunds.method(m).count > 0 || open.byMethod.containsKey(m);
            if (!methodActive) {
                continue;
            }

            // Every provider of an active method is listed (zero-filled) so the breakdown is complete and
            // stable; the unknown bucket (provider = null) is always last and is never merged into another.
            List<PaymentProvider> providers = new ArrayList<>(PaymentProvider.forMethod(m));
            providers.add(null);

            for (PaymentProvider p : providers) {
                Acc c = cur.provider(m, p);
                Acc pv = prev.provider(m, p);
                Acc r = refunds.provider(m, p);
                Map<PaymentStatus, Acc> o = open.byProvider.get(new ProviderKey(m, p));
                BigDecimal gross = MoneyMath.money(c.amount);
                BigDecimal refunded = MoneyMath.money(r.amount);

                String key = p != null ? p.name()
                        : (m == PaymentMethod.UPI ? UNKNOWN_UPI : UNKNOWN_WALLET);
                String label = p != null ? p.label()
                        : (m == PaymentMethod.UPI ? "Unknown UPI app" : "Unknown wallet");

                rows.add(new ProviderRow(
                        key, label, m.name(), m.label(), p != null,
                        gross,
                        c.count,
                        MoneyMath.percentOf(gross, MoneyMath.money(methodAcc.amount)),
                        MoneyMath.percentOf(gross, totals.gross()),
                        MoneyMath.average(gross, c.count),
                        refunded,
                        r.count,
                        MoneyMath.money(pv.amount),
                        MoneyMath.percentChange(gross, MoneyMath.money(pv.amount)),
                        OpenFolded.countOf(o, PaymentStatus.PENDING),
                        OpenFolded.countOf(o, PaymentStatus.FAILED),
                        OpenFolded.countOf(o, PaymentStatus.CANCELLED)));
            }
        }
        return rows;
    }

    private static Series series(AnalyticsInput in, List<LocalDateTime> buckets) {
        Map<LocalDateTime, Integer> index = new HashMap<>();
        for (int i = 0; i < buckets.size(); i++) {
            index.put(buckets.get(i), i);
        }

        Map<PaymentMethod, BigDecimal[]> amounts = new EnumMap<>(PaymentMethod.class);
        Map<PaymentMethod, long[]> counts = new EnumMap<>(PaymentMethod.class);

        for (HourlyFact f : in.collected()) {
            Integer i = index.get(in.granularity().floor(f.hourStart()));
            if (i == null) {
                continue; // outside the requested range; cannot happen for correctly filtered input
            }
            BigDecimal[] a = amounts.computeIfAbsent(f.method(), k -> zeros(buckets.size()));
            long[] n = counts.computeIfAbsent(f.method(), k -> new long[buckets.size()]);
            a[i] = a[i].add(f.amount() == null ? BigDecimal.ZERO : f.amount());
            n[i] += f.count() == null ? 0 : f.count();
        }

        List<SeriesRow> rows = new ArrayList<>();
        for (PaymentMethod m : PaymentMethod.values()) {
            BigDecimal[] a = amounts.get(m);
            if (a == null) {
                continue;
            }
            long[] n = counts.get(m);
            List<BigDecimal> amountList = new ArrayList<>();
            List<Long> countList = new ArrayList<>();
            for (int i = 0; i < a.length; i++) {
                amountList.add(MoneyMath.money(a[i]));
                countList.add(n[i]);
            }
            rows.add(new SeriesRow(m.name(), m.label(), amountList, countList));
        }
        return new Series(in.granularity().name(), buckets, rows);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static Heatmap heatmap(List<HourlyFact> collected) {
        BigDecimal[][] amt = new BigDecimal[7][24];
        long[][] cnt = new long[7][24];
        for (BigDecimal[] row : amt) {
            java.util.Arrays.fill(row, BigDecimal.ZERO);
        }
        BigDecimal[] hourAmt = zeros(24);
        long[] hourCnt = new long[24];

        for (HourlyFact f : collected) {
            LocalDateTime t = f.hourStart();
            int day = t.getDayOfWeek().getValue() - 1; // Monday = 0
            int hour = t.getHour();
            BigDecimal a = f.amount() == null ? BigDecimal.ZERO : f.amount();
            long c = f.count() == null ? 0 : f.count();
            amt[day][hour] = amt[day][hour].add(a);
            cnt[day][hour] += c;
            hourAmt[hour] = hourAmt[hour].add(a);
            hourCnt[hour] += c;
        }

        List<List<BigDecimal>> amounts = new ArrayList<>();
        List<List<Long>> counts = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            List<BigDecimal> ar = new ArrayList<>();
            List<Long> cr = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                ar.add(MoneyMath.money(amt[d][h]));
                cr.add(cnt[d][h]);
            }
            amounts.add(ar);
            counts.add(cr);
        }
        List<BigDecimal> ha = new ArrayList<>();
        List<Long> hc = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            ha.add(MoneyMath.money(hourAmt[h]));
            hc.add(hourCnt[h]);
        }
        return new Heatmap(amounts, counts, ha, hc);
    }

    /**
     * Gross sales is what was billed, tax and service charge included. Tax is owed onward, so the
     * figure a restaurant should read as what it earned is gross sales less ALL tax (added on top, or
     * already inside the prices). The service charge stays in: it is billed by the restaurant.
     * Never below zero, even if the figures disagree.
     */
    static Sales salesOf(AnalyticsInput.Sales in) {
        BigDecimal gross = MoneyMath.money(in.grossSales());
        BigDecimal collected = MoneyMath.money(in.taxCollected());
        BigDecimal included = MoneyMath.money(in.taxIncluded());
        BigDecimal exTax = MoneyMath.money(gross.subtract(collected).subtract(included).max(BigDecimal.ZERO));
        return new Sales(gross, in.ordersPlaced(), collected, included,
                MoneyMath.money(in.serviceCharges()), exTax);
    }
}
