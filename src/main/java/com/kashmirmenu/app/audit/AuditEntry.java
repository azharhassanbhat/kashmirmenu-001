package com.kashmirmenu.app.audit;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One line of the audit log. Written once and never modified: there is no update path in the
 * application, and the read API is the only way the rows leave the database.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** Deliberately a plain id, not an association: the record outlives the account. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_email", length = 150)
    private String actorEmail;

    @Column(name = "actor_role", length = 40)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private AuditAction action;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    /** Context for a human reader. Never credentials, tokens or card data. */
    @Column(length = 500)
    private String detail;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
