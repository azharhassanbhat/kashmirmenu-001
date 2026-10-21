package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.CancelReason;
import com.kashmirmenu.app.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row of an order's append-only status history. Never updated or deleted by the application. */
@Entity
@Table(name = "order_status_events")
@Getter
@Setter
@NoArgsConstructor
public class OrderStatusEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    /** null for the first event: the order being placed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private OrderStatus toStatus;

    @Column(name = "actor_label", nullable = false, length = 120, updatable = false)
    private String actorLabel;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, updatable = false)
    private CancelReason reason;

    @Column(length = 200, updatable = false)
    private String note;
}
