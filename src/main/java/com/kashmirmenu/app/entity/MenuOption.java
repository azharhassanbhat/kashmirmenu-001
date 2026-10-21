package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One choice in a group, with its price change from the dish's base price. See V35. */
@Entity
@Table(name = "menu_options")
@Getter
@Setter
@NoArgsConstructor
public class MenuOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private MenuOptionGroup group;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceDelta = BigDecimal.ZERO;

    @Column(nullable = false)
    private Boolean available = true;

    /** Pre-selected in the picker. Only a convenience: the server never assumes a choice. */
    @Column(name = "is_default", nullable = false)
    private Boolean defaultOption = false;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;
}
