package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.CardType;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment component. An order has one or more of them (split bills, retries after a failure);
 * the order's totals are always derived from these records. Money is exact DECIMAL(10,2).
 */
@Entity
@Table(
        name = "payments",
        indexes = {
                @Index(name = "idx_payments_order", columnList = "order_id"),
                @Index(name = "idx_payments_restaurant_time", columnList = "restaurant_id, payment_timestamp"),
                @Index(name = "idx_payments_restaurant_status_time",
                        columnList = "restaurant_id, payment_status, payment_timestamp")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payments_idempotency", columnNames = {"restaurant_id", "idempotency_key"})
)
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * The settlement this payment is part of, if a guest paid through the QR flow. Null for payments
     * staff recorded at the counter and for single-order payments made before settlements existed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private Settlement settlement;

    /** Denormalised from the order so analytics can be scoped and indexed per restaurant without a join. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    /** UPI app or wallet. Null means "not recorded" and is reported as Unknown; never inferred. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider")
    private PaymentProvider paymentProvider;

    /** Credit / debit / other. Only the kind is kept, never any card number or credential. */
    @Enumerated(EnumType.STRING)
    @Column(name = "card_type")
    private CardType cardType;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    /** The gateway / UPI / terminal reference, when one was supplied. */
    @Column(name = "transaction_id")
    private String transactionId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** When the money was received (set on capture). Kept for compatibility; see paymentTimestamp. */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * The time analytics uses: creation time while pending, and the time of the outcome once the payment
     * is captured, failed or cancelled.
     */
    @Column(name = "payment_timestamp", nullable = false)
    private LocalDateTime paymentTimestamp;

    /** Makes a replayed client request return the original payment instead of creating another. */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /** The staff member who confirmed or recorded the payment (null for payments only chosen by a diner). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_user_id")
    private User recordedBy;

    /** Set when a manager marks a digital payment as settled into the bank. Null = not (yet) settled. */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}
