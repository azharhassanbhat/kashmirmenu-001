package com.kashmirmenu.app.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;

public interface AuditRepository extends JpaRepository<AuditEntry, Long>,
        JpaSpecificationExecutor<AuditEntry> {

    // Filtering is done with AuditSpecifications, which adds only the predicates actually supplied.
    // A single query with ":param is null or ..." for each filter reads more simply but prevents the
    // planner from using idx_audit_action and idx_audit_time.

    long countByActionAndOccurredAtAfter(AuditAction action, LocalDateTime after);
}
