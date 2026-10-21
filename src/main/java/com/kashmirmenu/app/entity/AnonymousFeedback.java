package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Feedback left from the landing page without signing in. No name, phone or device is stored. See V41. */
@Entity
@Table(name = "anonymous_feedback")
@Getter
@Setter
@NoArgsConstructor
public class AnonymousFeedback extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    /** Only when the guest named an order that belongs to this restaurant. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", updatable = false)
    private Order order;

    @Column(nullable = false)
    private Integer rating;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(length = 1000)
    private String comment;

    /** Filled only when the guest ticked "you may contact me". */
    @Column(length = 100)
    private String contact;
}
