package com.kashmirmenu.app.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filters for the audit viewer, built from only the criteria actually supplied.
 *
 * <p>This replaces a single JPQL query that used the {@code :param is null or …} idiom for every
 * filter. That shape is convenient but the planner cannot know at plan time which branch applies, so
 * it generally cannot use an index for those predicates - and {@code audit_log} is one of only two
 * tables here that grows without bound, so a scan there gets worse on its own.
 *
 * <p>Adding a predicate only when its value is present lets {@code idx_audit_action} and
 * {@code idx_audit_time} be used when those filters are set.
 */
public final class AuditSpecifications {

    private AuditSpecifications() {
    }

    /**
     * @param actions    statuses to include; null or empty means no restriction
     * @param actorEmail matched as a PREFIX, not a substring - see below
     * @param from       inclusive lower bound on occurredAt
     * @param to         exclusive upper bound on occurredAt
     */
    public static Specification<AuditEntry> matching(
            Collection<AuditAction> actions,
            String actorEmail,
            LocalDateTime from,
            LocalDateTime to) {

        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();

            if (actions != null && !actions.isEmpty()) {
                all.add(root.get("action").in(actions));
            }

            if (actorEmail != null && !actorEmail.isBlank()) {
                // A prefix match ('alice%'), not a substring ('%alice%'): a leading wildcard cannot
                // use an index at all. Searching the audit log by the start of an address covers the
                // realistic case - you know who you are looking for - and stays indexable if an index
                // on actor_email is added later.
                all.add(cb.like(cb.lower(root.get("actorEmail")),
                        actorEmail.trim().toLowerCase() + "%"));
            }

            if (from != null) {
                all.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("occurredAt"), from));
            }
            if (to != null) {
                all.add(cb.lessThan(root.<LocalDateTime>get("occurredAt"), to));
            }

            return all.isEmpty() ? cb.conjunction() : cb.and(all.toArray(new Predicate[0]));
        };
    }
}
