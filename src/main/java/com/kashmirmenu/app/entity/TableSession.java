package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.TableSessionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One visit to a table. Every guest who scans the table's QR code while the visit is OPEN joins the
 * same visit; once it is closed, the next scan starts a new one, so guests from different meals are
 * never mixed.
 *
 * <p>The database guarantees at most one OPEN visit per table (V31). The service also holds the
 * table row's lock while it opens one, so two simultaneous first scans cannot each create a visit.
 */
@Entity
@Table(
        name = "table_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_table_sessions_visit_code", columnNames = "visit_code"))
@Getter
@Setter
@NoArgsConstructor
public class TableSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The visit's public identifier. Random, so it is safe to show; it grants nothing by itself. */
    @Column(name = "visit_code", nullable = false, updatable = false, length = 36)
    private String visitCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_table_id", nullable = false)
    private RestaurantTable restaurantTable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableSessionStatus status = TableSessionStatus.OPEN;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    /**
     * Set once, when the visit opens, and never updated afterwards, on purpose: carts, orders and
     * settlements hold a shared lock on this row (their foreign key), and updating it from them, or
     * from a join running beside them, can deadlock two guests acting at once. For idle expiry use the
     * newest {@link DinerSession#getLastSeenAt()} among the visit's guests, which every guest action
     * refreshes on their own row.
     */
    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_user_id")
    private User closedBy;

    @Column(name = "close_reason", length = 30)
    private String closeReason;

    @PrePersist
    void assignVisitCode() {
        if (visitCode == null || visitCode.isBlank()) {
            visitCode = UUID.randomUUID().toString();
        }
    }
}
