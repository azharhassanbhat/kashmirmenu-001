package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(nullable = false, length = 150)
    private String itemName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

    /**
     * How many units this line actually took out of stock when the order was placed.
     *
     * <p>Zero for an untracked dish, and zero for every row that predates migration V26. Restoration
     * gives back exactly this, rather than re-deriving it from whether the dish happens to be tracked
     * now - which credited stock that was never taken.
     */
    @Builder.Default
    @Column(name = "stock_consumed", nullable = false)
    private Integer stockConsumed = 0;

    /** What the guest chose, copied from the cart line. Plain copies: the menu can change freely. */
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<OrderItemOption> options = new java.util.ArrayList<>();
}