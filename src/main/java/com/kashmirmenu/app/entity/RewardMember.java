package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A guest's optional rewards membership at one restaurant. The guest's secret never reaches the server except
 * to be hashed; {@code memberCode} is what they read out to staff. The balance changes only through
 * conditional UPDATEs in the repository, never by setting the field; {@code @DynamicUpdate} means a stale copy of this
 * row can never write an old balance back, because only changed columns are ever written. See V41.
 */
@Entity
@DynamicUpdate
@Table(
        name = "reward_members",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reward_member_code", columnNames = {"restaurant_id", "member_code"}),
                @UniqueConstraint(name = "uk_reward_member_secret", columnNames = {"restaurant_id", "secret_hash"})
        })
@Getter
@Setter
@NoArgsConstructor
public class RewardMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @Column(name = "member_code", nullable = false, length = 8, updatable = false)
    private String memberCode;

    @Column(name = "secret_hash", nullable = false, length = 64, updatable = false)
    private String secretHash;

    /** Starts at 0. Never set from Java after creation: only the repository's conditional UPDATEs change it. */
    @Column(name = "points_balance", nullable = false)
    private Integer pointsBalance = 0;

    @Column(name = "lifetime_points", nullable = false)
    private Integer lifetimePoints = 0;
}
