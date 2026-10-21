package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * An option as it was ordered. A plain copy with no link back to the menu, so what the kitchen was
 * told and what the guest paid cannot change when the menu does. See V35.
 */
@Entity
@Table(name = "order_item_options")
@Getter
@Setter
@NoArgsConstructor
public class OrderItemOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
    private OrderItem orderItem;

    @Column(name = "group_name", nullable = false, length = 80, updatable = false)
    private String groupName;

    @Column(name = "option_name", nullable = false, length = 80, updatable = false)
    private String optionName;

    @Column(name = "price_delta", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal priceDelta;
}
