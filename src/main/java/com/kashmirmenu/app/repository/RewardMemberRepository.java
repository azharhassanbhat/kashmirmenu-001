package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.RewardMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RewardMemberRepository extends JpaRepository<RewardMember, Long> {

    Optional<RewardMember> findByRestaurantIdAndSecretHash(Long restaurantId, String secretHash);

    Optional<RewardMember> findByRestaurantIdAndMemberCode(Long restaurantId, String memberCode);

    /** Adds points. The only way a balance goes up; the amount comes from the server's own working-out. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
update RewardMember m
set m.pointsBalance = m.pointsBalance + :points,
    m.lifetimePoints = m.lifetimePoints + :points,
    m.updatedAt = :now
where m.id = :id
""")
    int addPoints(@Param("id") Long id, @Param("points") int points, @Param("now") LocalDateTime now);

    /**
     * Spends points, only while the balance covers them: one conditional UPDATE, so two staff redeeming at the
     * same moment cannot both succeed on the same points, and the balance cannot go below zero. Returns 0 when
     * the balance was too low.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
update RewardMember m
set m.pointsBalance = m.pointsBalance - :points, m.updatedAt = :now
where m.id = :id and m.pointsBalance >= :points
""")
    int spendPoints(@Param("id") Long id, @Param("points") int points, @Param("now") LocalDateTime now);
}
