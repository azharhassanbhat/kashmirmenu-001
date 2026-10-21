package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "categories",

        indexes = {

                @Index(
                        name = "idx_category_restaurant",
                        columnList = "restaurant_id"
                ),

                @Index(
                        name = "idx_category_active",
                        columnList = "active"
                )
        },

        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_restaurant_category_name",
                        columnNames = {"restaurant_id", "name"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "restaurant_id",
            nullable = false
    )
    private Restaurant restaurant;
}