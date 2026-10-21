package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One change to a member's balance. EARN rows (positive points) name the order that earned them, and the
 * database allows each order only once. REDEEM rows (negative points) are written when staff redeem a reward.
 * Rows are never updated or deleted. See V41.
 */
@Entity
@Table(
        name = "reward_ledger",
        uniqueConstraints = @UniqueConstraint(name = "uk_reward_ledger_order", columnNames = "order_id"))
@Getter
@Setter
@NoArgsConstructor
public class RewardLedger extends BaseEntity {

    public static final String EARN = "EARN";
    public static final String REDEEM = "REDEEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false)
    private RewardMember member;

    @Column(nullable = false, length = 10, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private Integer points;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", updatable = false)
    private Order order;

    @Column(length = 200, updatable = false)
    private String note;
}
