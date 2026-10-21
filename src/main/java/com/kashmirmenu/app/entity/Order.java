package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "orders",
        // Order numbers count per restaurant (ORD-1, ORD-2, ...), so every restaurant has its own
        // ORD-1. Uniqueness therefore holds within a restaurant, not across the table.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_order_restaurant_number",
                columnNames = {"restaurant_id", "order_number"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_table_id")
    private RestaurantTable restaurantTable;

    /** The visit and guest that placed the order. Null for orders staff placed themselves, and for older orders. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_session_id")
    private TableSession tableSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diner_session_id")
    private DinerSession dinerSession;

    /** The guest's display name when the order was placed. Copied, so listings need no join. */
    @Column(name = "diner_name", length = 60)
    private String dinerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal tax = BigDecimal.ZERO;

    /** Service charge added to the total (see OrderCharges). 0 when the restaurant charges none. */
    @Builder.Default
    @Column(name = "service_charge", nullable = false, precision = 10, scale = 2)
    private BigDecimal serviceCharge = BigDecimal.ZERO;

    /** Tax already contained in the dish prices, for display only; never added to the total. */
    @Builder.Default
    @Column(name = "tax_included", nullable = false, precision = 10, scale = 2)
    private BigDecimal taxIncluded = BigDecimal.ZERO;

    /* The rules these amounts were worked out with, copied from the restaurant's settings. */
    @Column(name = "tax_name", length = 30)
    private String taxName;

    @Builder.Default
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "service_charge_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceChargeRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "prices_include_tax", nullable = false)
    private Boolean pricesIncludeTax = false;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(length = 1000)
    private String notes;

    /** Why staff cancelled it. null when it was not cancelled, or was cancelled without a reason. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private com.kashmirmenu.app.enums.CancelReason cancelReason;

    /** Staff's own words. Internal: never shown to the guest. */
    @Column(name = "cancel_note", length = 200)
    private String cancelNote;

    @Column(name = "cancelled_at")
    private java.time.LocalDateTime cancelledAt;

    /** The staff email that cancelled it, copied as text. */
    @Column(name = "cancelled_by", length = 120)
    private String cancelledBy;

    /** Nulled if the coupon is later deleted; couponCode keeps the order's own history readable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    /**
     * Credential for the public tracking URL. The order number is short and sequential so diners can
     * read it aloud; this is random so it cannot be counted through. Never shown as an identifier.
     */
    @Column(name = "public_token", nullable = false, length = 43, updatable = false)
    private String publicToken;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();
}