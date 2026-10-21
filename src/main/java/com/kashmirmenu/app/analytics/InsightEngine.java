package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.analytics.AnalyticsOverview.Insight;
import com.kashmirmenu.app.analytics.AnalyticsOverview.MethodRow;
import com.kashmirmenu.app.analytics.AnalyticsOverview.OpenItems;
import com.kashmirmenu.app.analytics.AnalyticsOverview.ProviderRow;
import com.kashmirmenu.app.analytics.AnalyticsOverview.Totals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates plain-language observations from the computed numbers. Each rule states a minimum sample
 * size and stays silent below it, so nothing is said that the transactions do not support.
 */
public final class InsightEngine {

    /** Fewest successful transactions in a period before any share/ranking statement is made. */
    static final long MIN_TRANSACTIONS = 5;
    /** Fewest transactions per method (in each period) before its growth or ticket size is compared. */
    static final long MIN_METHOD_TRANSACTIONS = 3;
    /** Hours of activity needed before a "peak hour" is meaningful. */
    static final long MIN_PEAK_TRANSACTIONS = 10;

    private static final BigDecimal DECLINE_THRESHOLD = BigDecimal.valueOf(-15);
    private static final BigDecimal UNKNOWN_SHARE_THRESHOLD = BigDecimal.valueOf(20);
    private static final BigDecimal REFUND_ALERT_PERCENT = BigDecimal.valueOf(5);

    private InsightEngine() {
    }

    public static List<Insight> generate(
            PeriodPreset preset,
            String currency,
            Totals cur,
            Totals prev,
            List<MethodRow> methods,
            List<ProviderRow> providers,
            List<BigDecimal> hourlyAmounts,
            List<Long> hourlyCounts,
            OpenItems open) {

        List<Insight> out = new ArrayList<>();
        String when = when(preset);
        boolean enough = cur.transactions() >= MIN_TRANSACTIONS;
        boolean previousEnough = prev.transactions() >= MIN_TRANSACTIONS;

        if (enough) {
            topMethod(out, when, currency, cur, methods);
            mostUsed(out, methods);
            upiApp(out, providers);
            unknownProvider(out, providers);
            digitalVersusCash(out, when, cur, prev, methods, previousEnough);
            if (previousEnough) {
                growth(out, methods);
            }
            averageTicket(out, currency, methods);
        }
        peakHour(out, currency, cur, hourlyAmounts, hourlyCounts);
        refunds(out, currency, cur);
        pending(out, currency, open);
        return out;
    }

    // ---- rules ----

    private static void topMethod(List<Insight> out, String when, String currency, Totals cur, List<MethodRow> methods) {
        MethodRow top = methods.stream().filter(m -> m.gross().signum() > 0).findFirst().orElse(null);
        if (top == null) {
            return;
        }
        long withMoney = methods.stream().filter(m -> m.gross().signum() > 0).count();
        String text = withMoney == 1
                ? "All collected payments " + when + " came through " + top.label() + " ("
                + MoneyMath.format(top.gross(), currency) + ")."
                : top.label() + " accounts for " + pct(top.shareOfCollected()) + "% of collected payments "
                + when + " (" + MoneyMath.format(top.gross(), currency) + ").";
        out.add(new Insight("top-method", "info", text));
    }

    private static void mostUsed(List<Insight> out, List<MethodRow> methods) {
        MethodRow byCount = methods.stream()
                .filter(m -> m.transactions() > 0)
                .max(Comparator.comparingLong(MethodRow::transactions))
                .orElse(null);
        MethodRow byMoney = methods.stream().filter(m -> m.gross().signum() > 0).findFirst().orElse(null);
        if (byCount == null || byMoney == null || byCount.key().equals(byMoney.key())) {
            return;
        }
        out.add(new Insight("most-used", "neutral",
                byCount.label() + " is used most often (" + byCount.transactions() + " payments), but "
                        + byMoney.label() + " brings in the most money."));
    }

    private static void upiApp(List<Insight> out, List<ProviderRow> providers) {
        List<ProviderRow> upi = providers.stream().filter(p -> "UPI".equals(p.method())).toList();
        long upiTx = upi.stream().mapToLong(ProviderRow::transactions).sum();
        if (upiTx < MIN_TRANSACTIONS) {
            return;
        }
        ProviderRow top = upi.stream()
                .filter(ProviderRow::known)
                .filter(p -> p.gross().signum() > 0)
                .max(Comparator.comparing(ProviderRow::gross))
                .orElse(null);
        if (top == null) {
            return;
        }
        out.add(new Insight("upi-app", "info",
                top.label() + " contributed " + pct(top.shareOfMethod()) + "% of UPI revenue."));
    }

    private static void unknownProvider(List<Insight> out, List<ProviderRow> providers) {
        for (ProviderRow p : providers) {
            if (p.known() || p.transactions() < MIN_METHOD_TRANSACTIONS) {
                continue;
            }
            if (p.shareOfMethod().compareTo(UNKNOWN_SHARE_THRESHOLD) >= 0) {
                boolean upi = "UPI".equals(p.method());
                out.add(new Insight("unknown-" + p.method().toLowerCase(), "info",
                        pct(p.shareOfMethod()) + "% of " + (upi ? "UPI" : "wallet")
                                + " revenue has no " + (upi ? "app" : "wallet") + " recorded. "
                                + "Choose it when confirming a payment to complete this breakdown."));
            }
        }
    }

    private static void digitalVersusCash(
            List<Insight> out, String when, Totals cur, Totals prev, List<MethodRow> methods, boolean previousEnough) {

        BigDecimal digital = methods.stream().filter(MethodRow::digital)
                .map(MethodRow::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (cur.gross().signum() <= 0) {
            return;
        }
        BigDecimal share = MoneyMath.percentOf(digital, cur.gross());
        String text = "Digital payments make up " + pct(share) + "% of collections " + when;

        if (previousEnough && prev.gross().signum() > 0) {
            BigDecimal prevDigital = methods.stream().filter(MethodRow::digital)
                    .map(MethodRow::previousGross).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal points = share.subtract(MoneyMath.percentOf(prevDigital, prev.gross()));
            if (points.abs().compareTo(BigDecimal.ONE) >= 0) {
                text += ", " + (points.signum() > 0 ? "up " : "down ") + pct(points.abs())
                        + " points from the previous period";
            }
        }
        out.add(new Insight("digital-vs-cash", "neutral", text + "."));
    }

    private static void growth(List<Insight> out, List<MethodRow> methods) {
        List<MethodRow> comparable = methods.stream()
                .filter(m -> m.deltaPercent() != null)
                .filter(m -> m.transactions() >= MIN_METHOD_TRANSACTIONS
                        && m.previousTransactions() >= MIN_METHOD_TRANSACTIONS)
                .toList();
        if (comparable.isEmpty()) {
            return;
        }
        MethodRow best = comparable.stream().max(Comparator.comparing(MethodRow::deltaPercent)).get();
        if (best.deltaPercent().signum() > 0) {
            out.add(new Insight("fastest-growing", "positive",
                    best.label() + " collections grew " + pct(best.deltaPercent())
                            + "% compared with the previous period."));
        }
        MethodRow worst = comparable.stream().min(Comparator.comparing(MethodRow::deltaPercent)).get();
        if (worst.deltaPercent().compareTo(DECLINE_THRESHOLD) <= 0 && !worst.key().equals(best.key())) {
            out.add(new Insight("declining", "negative",
                    worst.label() + " collections fell " + pct(worst.deltaPercent().abs())
                            + "% compared with the previous period."));
        }
    }

    private static void averageTicket(List<Insight> out, String currency, List<MethodRow> methods) {
        List<MethodRow> eligible = methods.stream()
                .filter(m -> m.transactions() >= MIN_METHOD_TRANSACTIONS)
                .toList();
        if (eligible.size() < 2) {
            return;
        }
        MethodRow high = eligible.stream().max(Comparator.comparing(MethodRow::averageTransaction)).get();
        MethodRow low = eligible.stream().min(Comparator.comparing(MethodRow::averageTransaction)).get();
        if (high.key().equals(low.key()) || low.averageTransaction().signum() <= 0) {
            return;
        }
        // Ignore trivial differences (under 10%).
        BigDecimal ratio = high.averageTransaction().divide(low.averageTransaction(), 2, java.math.RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("1.10")) < 0) {
            return;
        }
        out.add(new Insight("avg-ticket", "neutral",
                high.label() + " has the highest average payment (" + MoneyMath.format(high.averageTransaction(), currency)
                        + ") against " + MoneyMath.format(low.averageTransaction(), currency) + " for " + low.label() + "."));
    }

    private static void peakHour(
            List<Insight> out, String currency, Totals cur, List<BigDecimal> amounts, List<Long> counts) {

        if (cur.transactions() < MIN_PEAK_TRANSACTIONS || cur.gross().signum() <= 0) {
            return;
        }
        long activeHours = counts.stream().filter(c -> c > 0).count();
        if (activeHours < 2) {
            return;
        }
        int peak = 0;
        for (int h = 1; h < amounts.size(); h++) {
            if (amounts.get(h).compareTo(amounts.get(peak)) > 0) {
                peak = h;
            }
        }
        BigDecimal share = MoneyMath.percentOf(amounts.get(peak), cur.gross());
        out.add(new Insight("peak-hour", "info",
                "Collections peak between " + hourLabel(peak) + " and " + hourLabel((peak + 1) % 24) + ": "
                        + MoneyMath.format(amounts.get(peak), currency) + " (" + pct(share) + "% of the total)."));
    }

    private static void refunds(List<Insight> out, String currency, Totals cur) {
        if (cur.refunds().signum() <= 0 || cur.gross().signum() <= 0) {
            return;
        }
        BigDecimal rate = MoneyMath.percentOf(cur.refunds(), cur.gross());
        boolean high = rate.compareTo(REFUND_ALERT_PERCENT) >= 0;
        out.add(new Insight("refunds", high ? "negative" : "neutral",
                MoneyMath.format(cur.refunds(), currency) + " was refunded across " + cur.refundCount()
                        + (cur.refundCount() == 1 ? " refund" : " refunds") + " (" + pct(rate) + "% of collections)."));
    }

    private static void pending(List<Insight> out, String currency, OpenItems open) {
        if (open.pendingCount() <= 0) {
            return;
        }
        out.add(new Insight("pending", "info",
                open.pendingCount() + (open.pendingCount() == 1 ? " payment" : " payments") + " ("
                        + MoneyMath.format(open.pendingAmount(), currency) + ") "
                        + (open.pendingCount() == 1 ? "is" : "are") + " waiting for confirmation and not counted as collected."));
    }

    // ---- wording helpers ----

    static String when(PeriodPreset preset) {
        return switch (preset) {
            case TODAY -> "today";
            case YESTERDAY -> "yesterday";
            case LAST_7_DAYS -> "in the last 7 days";
            case LAST_30_DAYS -> "in the last 30 days";
            case THIS_MONTH -> "this month";
            case LAST_MONTH -> "last month";
            case CUSTOM -> "in the selected period";
        };
    }

    static String hourLabel(int hour) {
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        return h12 + (hour < 12 ? " AM" : " PM");
    }

    /** 64.0 -> "64", 42.5 -> "42.5". */
    static String pct(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) {
            s = s.setScale(0);
        }
        return s.toPlainString();
    }
}
