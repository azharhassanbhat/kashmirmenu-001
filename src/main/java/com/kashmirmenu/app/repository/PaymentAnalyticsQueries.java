package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.analytics.AnalyticsInput;
import com.kashmirmenu.app.analytics.DateRange;
import com.kashmirmenu.app.analytics.HourlyFact;
import com.kashmirmenu.app.analytics.OpenFact;
import com.kashmirmenu.app.analytics.PaymentFilter;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Database-side aggregation for the payments dashboard. Nothing here loads individual payments: each
 * method returns a handful of grouped rows. The JPQL is assembled from fixed fragments and every value
 * is a named parameter, so no request data is ever concatenated into a query.
 */
@Repository
public class PaymentAnalyticsQueries {

    private static final String FACT = "com.kashmirmenu.app.analytics.HourlyFact";
    private static final String OPEN_FACT = "com.kashmirmenu.app.analytics.OpenFact";

    @PersistenceContext
    private EntityManager em;

    /** A where-clause under construction plus its bound parameters. */
    private static final class Where {
        final List<String> clauses = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();

        void add(String clause) {
            clauses.add(clause);
        }

        String sql() {
            return String.join(" and ", clauses);
        }

        <T> TypedQuery<T> bind(TypedQuery<T> q) {
            params.forEach(q::setParameter);
            return q;
        }
    }

    /**
     * The filters that act on the payment itself. {@code p} is the Payment alias; the time column
     * and status handling differ per query so they are added by the callers.
     */
    private static void paymentFilters(Where w, PaymentFilter f) {
        w.add("p.restaurant.id = :rid");
        w.params.put("rid", f.restaurantId());

        if (!f.methods().isEmpty()) {
            w.add("p.paymentMethod in :methods");
            w.params.put("methods", f.methods());
        }
        if (f.hasProviderFilter()) {
            List<String> any = new ArrayList<>();
            if (!f.providers().isEmpty()) {
                any.add("p.paymentProvider in :providers");
                w.params.put("providers", f.providers());
            }
            if (!f.unknownProviderOf().isEmpty()) {
                any.add("(p.paymentProvider is null and p.paymentMethod in :unknownOf)");
                w.params.put("unknownOf", f.unknownProviderOf());
            }
            w.add("(" + String.join(" or ", any) + ")");
        }
        if (f.staffId() != null) {
            w.add("p.recordedBy.id = :staffId");
            w.params.put("staffId", f.staffId());
        }
    }

    private static void timeRange(Where w, String column, DateRange r) {
        w.add(column + " >= :start");
        w.add(column + " < :end");
        w.params.put("start", r.start());
        w.params.put("end", r.endExclusive());
    }

    private static String hourParts(String column) {
        return "extract(year from " + column + "), extract(month from " + column + "), "
                + "extract(day from " + column + "), extract(hour from " + column + ")";
    }

    /** Received money (captured payments) grouped by method, provider and business-local hour. */
    public List<HourlyFact> collected(PaymentFilter f, DateRange range) {
        Set<PaymentStatus> statuses = f.capturedStatuses();
        if (statuses.isEmpty() || range.isEmpty()) {
            return List.of();
        }
        Where w = new Where();
        paymentFilters(w, f);
        timeRange(w, "p.paymentTimestamp", range);
        w.add("p.paymentStatus in :statuses");
        w.params.put("statuses", statuses);

        String col = "p.paymentTimestamp";
        String jpql = "select new " + FACT + "(p.paymentMethod, p.paymentProvider, " + hourParts(col)
                + ", count(p), sum(p.amount)) from Payment p where " + w.sql()
                + " group by p.paymentMethod, p.paymentProvider, " + hourParts(col);
        return w.bind(em.createQuery(jpql, HourlyFact.class)).getResultList();
    }

    /** Refunds issued in the range (cash basis), for payments matching the filters. */
    public List<HourlyFact> refunds(PaymentFilter f, DateRange range) {
        if (!f.includesRefunds() || range.isEmpty()) {
            return List.of();
        }
        Where w = new Where();
        paymentFilters(w, f);
        timeRange(w, "r.refundedAt", range);

        String col = "r.refundedAt";
        String jpql = "select new " + FACT + "(p.paymentMethod, p.paymentProvider, " + hourParts(col)
                + ", count(r), sum(r.amount)) from PaymentRefund r join r.payment p where " + w.sql()
                + " group by p.paymentMethod, p.paymentProvider, " + hourParts(col);
        return w.bind(em.createQuery(jpql, HourlyFact.class)).getResultList();
    }

    /** Pending, failed and cancelled payments in the range. Reported separately, never as revenue. */
    public List<OpenFact> open(PaymentFilter f, DateRange range) {
        Set<PaymentStatus> statuses = f.openStatuses();
        if (statuses.isEmpty() || range.isEmpty()) {
            return List.of();
        }
        Where w = new Where();
        paymentFilters(w, f);
        timeRange(w, "p.paymentTimestamp", range);
        w.add("p.paymentStatus in :statuses");
        w.params.put("statuses", statuses);

        String jpql = "select new " + OPEN_FACT + "(p.paymentStatus, p.paymentMethod, p.paymentProvider, "
                + "count(p), sum(p.amount)) from Payment p where " + w.sql()
                + " group by p.paymentStatus, p.paymentMethod, p.paymentProvider";
        return w.bind(em.createQuery(jpql, OpenFact.class)).getResultList();
    }

    /** Distinct orders that received at least one captured payment: the "orders paid" figure. */
    public long uniqueOrdersPaid(PaymentFilter f, DateRange range) {
        Set<PaymentStatus> statuses = f.capturedStatuses();
        if (statuses.isEmpty() || range.isEmpty()) {
            return 0;
        }
        Where w = new Where();
        paymentFilters(w, f);
        timeRange(w, "p.paymentTimestamp", range);
        w.add("p.paymentStatus in :statuses");
        w.params.put("statuses", statuses);

        String jpql = "select count(distinct p.order.id) from Payment p where " + w.sql();
        Long n = w.bind(em.createQuery(jpql, Long.class)).getSingleResult();
        return n == null ? 0 : n;
    }

    /** Orders placed in the range (cancelled excluded): the billing figure, independent of payments. */
    public AnalyticsInput.Sales sales(Long restaurantId, DateRange range) {
        if (range.isEmpty()) {
            return new AnalyticsInput.Sales(BigDecimal.ZERO, 0);
        }
        String jpql = "select count(o), sum(o.total), sum(o.tax), sum(o.taxIncluded), sum(o.serviceCharge) "
                + "from Order o where o.restaurant.id = :rid "
                + "and o.createdAt >= :start and o.createdAt < :end and o.status <> :cancelled";
        Object[] row = em.createQuery(jpql, Object[].class)
                .setParameter("rid", restaurantId)
                .setParameter("start", range.start())
                .setParameter("end", range.endExclusive())
                .setParameter("cancelled", OrderStatus.CANCELLED)
                .getSingleResult();
        long orders = row[0] == null ? 0 : ((Number) row[0]).longValue();
        BigDecimal total = sum(row[1]);
        // Tax and service charge are summed on the same orders, so the parts always add up to the whole.
        return new AnalyticsInput.Sales(total, orders, sum(row[2]), sum(row[3]), sum(row[4]));
    }

    /** Cash received per recording staff member (null id = recorded by nobody, e.g. legacy rows). */
    public List<Object[]> cashByStaff(Long restaurantId, DateRange range) {
        if (range.isEmpty()) {
            return List.of();
        }
        String jpql = "select u.id, u.name, count(p), sum(p.amount) from Payment p left join p.recordedBy u "
                + "where p.restaurant.id = :rid and p.paymentTimestamp >= :start and p.paymentTimestamp < :end "
                + "and p.paymentStatus in :captured and p.paymentMethod = :cash "
                + "group by u.id, u.name order by sum(p.amount) desc";
        return em.createQuery(jpql, Object[].class)
                .setParameter("rid", restaurantId)
                .setParameter("start", range.start())
                .setParameter("end", range.endExclusive())
                .setParameter("captured", PaymentStatus.CAPTURED)
                .setParameter("cash", PaymentMethod.CASH)
                .getResultList();
    }

    /** [count, sum] of captured digital payments in the range that are (or are not yet) marked settled. */
    public Object[] digitalSettlement(Long restaurantId, DateRange range, boolean settled) {
        if (range.isEmpty()) {
            return new Object[]{0L, BigDecimal.ZERO};
        }
        String jpql = "select count(p), sum(p.amount) from Payment p "
                + "where p.restaurant.id = :rid and p.paymentTimestamp >= :start and p.paymentTimestamp < :end "
                + "and p.paymentStatus in :captured and p.paymentMethod in :digital and p.settledAt is "
                + (settled ? "not null" : "null");
        return em.createQuery(jpql, Object[].class)
                .setParameter("rid", restaurantId)
                .setParameter("start", range.start())
                .setParameter("end", range.endExclusive())
                .setParameter("captured", PaymentStatus.CAPTURED)
                .setParameter("digital", digitalMethods())
                .getSingleResult();
    }

    /** Marks the day's captured digital payments as settled. Returns how many rows changed. */
    public int markDigitalSettled(Long restaurantId, DateRange range, LocalDateTime now) {
        if (range.isEmpty()) {
            return 0;
        }
        String jpql = "update Payment p set p.settledAt = :now, p.updatedAt = :now "
                + "where p.restaurant.id = :rid and p.paymentTimestamp >= :start and p.paymentTimestamp < :end "
                + "and p.paymentStatus in :captured and p.paymentMethod in :digital and p.settledAt is null";
        return em.createQuery(jpql)
                .setParameter("now", now)
                .setParameter("rid", restaurantId)
                .setParameter("start", range.start())
                .setParameter("end", range.endExclusive())
                .setParameter("captured", PaymentStatus.CAPTURED)
                .setParameter("digital", digitalMethods())
                .executeUpdate();
    }

    public static Set<PaymentMethod> digitalMethods() {
        Set<PaymentMethod> digital = EnumSet.noneOf(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) {
            if (m.isDigital()) {
                digital.add(m);
            }
        }
        return digital;
    }

    /** A SUM over no rows is null; here it means zero. */
    private static BigDecimal sum(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }
}
