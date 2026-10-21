package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.CartStatus;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_table_id")
    private RestaurantTable restaurantTable;

    /**
     * The visit and guest this cart belongs to. Both null means a staff cart: the one cart per table
     * that the staff API works on. A guest's cart is never visible to another guest.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_session_id")
    private TableSession tableSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diner_session_id")
    private DinerSession dinerSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CartStatus status = CartStatus.ACTIVE;

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

    /**
     * The coupon the table is holding, if any. Re-checked on every change to the cart, so a coupon
     * that stops qualifying (an item removed below its minimum, or it expires mid-meal) is dropped
     * rather than silently discounting the bill.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @OneToMany(
            mappedBy = "cart",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

}