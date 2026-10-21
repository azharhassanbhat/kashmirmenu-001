package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.SettlementKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One act of paying: a guest settling their own orders (SEPARATE) or the whole table's (COMBINED).
 * It owns no money itself. It groups the payments rows that make it up, one per covered order, so the
 * order-level balance, refunds and revenue figures all keep working unchanged.
 *
 * <p>There is no status field: the status is worked out from the payments (SettlementRules.statusOf),
 * so it cannot disagree with them.
 */
@Entity
@Table(
        name = "settlements",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_settlements_code", columnNames = "settlement_code"),
                @UniqueConstraint(name = "uk_settlements_payer_key", columnNames = {"payer_id", "idempotency_key"})
        })
@Getter
@Setter
@NoArgsConstructor
public class Settlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Random public identifier, so settlements are not reachable by counting ids. */
    @Column(name = "settlement_code", nullable = false, updatable = false, length = 36)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "table_session_id", nullable = false, updatable = false)
    private TableSession tableSession;

    /** The guest who is paying, which for a combined settlement is not who ordered everything. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payer_id", nullable = false, updatable = false)
    private DinerSession payer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SettlementKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, updatable = false, length = 20)
    private PaymentMethod paymentMethod;

    /** What was asked for when it started. What was actually received is in its payments. */
    @Column(nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal total;

    /** Supplied by the client; the same key from the same guest returns this settlement again. */
    @Column(name = "idempotency_key", updatable = false, length = 80)
    private String idempotencyKey;

    /**
     * When the paying guest said "I've completed payment". A claim only: the payments stay PENDING until
     * staff confirm the money arrived.
     */
    @Column(name = "customer_confirmed_at")
    private java.time.LocalDateTime customerConfirmedAt;

    /** The UPI transaction reference the guest typed in, if any. Validated; never trusted as proof. */
    @Column(name = "customer_reference", length = 40)
    private String customerReference;

    @PrePersist
    void assignCode() {
        if (code == null || code.isBlank()) {
            code = UUID.randomUUID().toString();
        }
    }
}
