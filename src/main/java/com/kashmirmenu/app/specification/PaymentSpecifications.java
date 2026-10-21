package com.kashmirmenu.app.specification;

import com.kashmirmenu.app.analytics.DateRange;
import com.kashmirmenu.app.analytics.PaymentFilter;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Payment;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Server-side filtering for the transaction history table and its CSV export. */
public final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    /**
     * @param search matches the gateway reference, the order number, or a payment id; null/blank = no search
     */
    public static Specification<Payment> matching(PaymentFilter f, DateRange range, String search) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();

            all.add(cb.equal(root.get("restaurant").get("id"), f.restaurantId()));
            all.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("paymentTimestamp"), range.start()));
            all.add(cb.lessThan(root.<LocalDateTime>get("paymentTimestamp"), range.endExclusive()));

            if (!f.methods().isEmpty()) {
                all.add(root.get("paymentMethod").in(f.methods()));
            }
            if (!f.statuses().isEmpty()) {
                all.add(root.get("paymentStatus").in(f.statuses()));
            }
            if (f.hasProviderFilter()) {
                List<Predicate> any = new ArrayList<>();
                if (!f.providers().isEmpty()) {
                    any.add(root.get("paymentProvider").in(f.providers()));
                }
                if (!f.unknownProviderOf().isEmpty()) {
                    any.add(cb.and(
                            cb.isNull(root.get("paymentProvider")),
                            root.get("paymentMethod").in(f.unknownProviderOf())));
                }
                all.add(cb.or(any.toArray(new Predicate[0])));
            }
            if (f.staffId() != null) {
                all.add(cb.equal(root.get("recordedBy").get("id"), f.staffId()));
            }

            if (search != null && !search.isBlank()) {
                String like = "%" + escapeLike(search.trim().toLowerCase()) + "%";
                Join<Payment, Order> order = root.join("order", JoinType.LEFT);
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(cb.coalesce(root.<String>get("transactionId"), "")), like, '\\'));
                any.add(cb.like(cb.lower(order.<String>get("orderNumber")), like, '\\'));
                String digits = search.trim().replaceFirst("(?i)^pay-", "");
                if (digits.matches("\\d{1,18}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(digits)));
                }
                all.add(cb.or(any.toArray(new Predicate[0])));
            }

            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
