package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Coupon;
import com.kashmirmenu.app.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByRestaurantAndCode(
            Restaurant restaurant,
            String code);

    boolean existsByRestaurantAndCode(
            Restaurant restaurant,
            String code);

    List<Coupon> findByRestaurant(
            Restaurant restaurant);

    /**
     * Looks up a code that the caller has already normalised with {@link CouponCode#normalise}.
     *
     * <p>Plain equality on purpose: codes are stored upper-cased (migration V29), so this uses
     * UNIQUE (restaurant_id, code) in full. The previous upper(c.code) = upper(:code) wrapped the
     * indexed column in a function, which made the code half of that index unusable.
     */
    @Query("select c from Coupon c where c.restaurant = :restaurant and c.code = :code")
    Optional<Coupon> findByRestaurantAndNormalisedCode(
            @Param("restaurant") Restaurant restaurant,
            @Param("code") String code);

    /**
     * Claims one use, in one statement, only if a use is still available. Returns 1 on success and 0
     * if the coupon ran out in the meantime, so the usage limit holds under concurrent checkouts
     * without locking the row in the application.
     */
    @Modifying
    @Query("""
update Coupon c
set c.usedCount = c.usedCount + 1, c.updatedAt = :now
where c.id = :id
and (c.usageLimit = 0 or c.usedCount < c.usageLimit)
""")
    int claimOneUse(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    /** Gives a use back when an order that redeemed one is cancelled. Never goes below zero. */
    @Modifying
    @Query("""
update Coupon c
set c.usedCount = c.usedCount - 1, c.updatedAt = :now
where c.id = :id and c.usedCount > 0
""")
    int releaseOneUse(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    List<Coupon> findByRestaurantAndActiveTrue(
            Restaurant restaurant);

    List<Coupon> findByRestaurantAndActiveTrueAndStartDateLessThanEqualAndExpiryDateGreaterThanEqual(
            Restaurant restaurant,
            LocalDateTime currentTime1,
            LocalDateTime currentTime2);
}