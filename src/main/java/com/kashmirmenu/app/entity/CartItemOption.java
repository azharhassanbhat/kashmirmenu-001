package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** A guest's choice on a cart line, copied as it was when chosen. See V35. */
@Entity
@Table(name = "cart_item_options")
@Getter
@Setter
@NoArgsConstructor
public class CartItemOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_item_id", nullable = false, updatable = false)
    private CartItem cartItem;

    /** Live link so placement can refuse an option switched off since; null once it is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", updatable = false)
    private MenuOption option;

    @Column(name = "group_name", nullable = false, length = 80, updatable = false)
    private String groupName;

    @Column(name = "option_name", nullable = false, length = 80, updatable = false)
    private String optionName;

    @Column(name = "price_delta", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal priceDelta;
}
