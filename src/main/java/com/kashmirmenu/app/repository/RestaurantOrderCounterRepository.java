package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.RestaurantOrderCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RestaurantOrderCounterRepository extends JpaRepository<RestaurantOrderCounter, Long> {

    /**
     * Locks the restaurant's counter row for the rest of the transaction, so two diners ordering at
     * the same moment take consecutive numbers instead of the same one.
     */
    /**
     * Creates the restaurant's counter row if it has none, and does nothing if it has one.
     *
     * <p>Without it, the first order a restaurant ever takes had a race: with no row there is
     * nothing to lock, so two guests ordering at that moment each inserted one and the loser failed
     * on the primary key. Run before findForUpdate, never after: reading a missing row first takes a
     * gap lock, and two transactions holding one then both inserting is a deadlock.
     */
    @Modifying
    @Query(value = "insert into restaurant_order_counters (restaurant_id, next_number, created_at, updated_at) "
            + "values (:restaurantId, 1, :now, :now) "
            + "on duplicate key update restaurant_id = restaurant_id", nativeQuery = true)
    void ensureExists(@Param("restaurantId") Long restaurantId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RestaurantOrderCounter c where c.restaurantId = :restaurantId")
    Optional<RestaurantOrderCounter> findForUpdate(@Param("restaurantId") Long restaurantId);
}
