package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A guest's private rating of a served order. One per order; see V39. */
@Entity
@Table(
        name = "order_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_feedback_order", columnNames = "order_id"))
@Getter
@Setter
@NoArgsConstructor
public class OrderFeedback extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @Column(nullable = false)
    private Integer rating;

    @Column(length = 500)
    private String comment;
}
