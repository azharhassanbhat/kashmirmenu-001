package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "menu_items",

        indexes = {

                @Index(
                        name = "idx_menu_restaurant",
                        columnList = "restaurant_id"
                ),

                @Index(
                        name = "idx_menu_category",
                        columnList = "category_id"
                ),

                @Index(
                        name = "idx_menu_available",
                        columnList = "available"
                ),

                @Index(
                        name = "idx_menu_active",
                        columnList = "active"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(length = 500)
    private String imageUrl;

    @Builder.Default
    private Boolean veg = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean available = true;

    @Builder.Default
    private Boolean active = true;

    @Builder.Default
    private Integer displayOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer preparationTime = 0;

    /** "Chef's recommendation". A restaurant's own pick, shown as such on the menu. */
    @Column(name = "chef_pick", nullable = false)
    @Builder.Default
    private Boolean chefPick = false;

    @Column(name = "todays_special", nullable = false)
    @Builder.Default
    private Boolean todaysSpecial = false;

    /**
     * Comma-separated {@code Allergen} codes the restaurant has declared. NULL means the restaurant
     * has not said anything (nothing is shown); "" means it declared that none apply.
     */
    @Column(length = 200)
    private String allergens;

    /** Comma-separated {@code DietaryLabel} codes. Same NULL/empty convention as allergens. */
    @Column(name = "dietary_labels", length = 200)
    private String dietaryLabels;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
}