package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantTable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface RestaurantTableRepository extends
        JpaRepository<RestaurantTable, Long>,
        JpaSpecificationExecutor<RestaurantTable> {

    List<RestaurantTable> findAllByRestaurant(Restaurant restaurant);

    Optional<RestaurantTable> findByQrIdentifier(String qrIdentifier);

    @Query(
            "select t from RestaurantTable t join fetch t.restaurant "
                    + "where t.qrIdentifier = :qrIdentifier")
    Optional<RestaurantTable> findWithRestaurantByQrIdentifier(
            @Param("qrIdentifier") String qrIdentifier);

    /**
     * Locks the table row for the rest of the transaction. Every cart operation for a table goes
     * through one of these two finders, which serialises them.
     *
     * <p>The lock is on the table rather than on the cart deliberately: a pessimistic lock on a
     * SELECT that matches no rows locks nothing, so locking the cart cannot stop two concurrent
     * first requests from each inserting an ACTIVE cart. The table row always exists.
     *
     * <p>LOCK ORDER, which every caller must keep: restaurant_tables -> coupons -> order counter ->
     * inventory. Taking them in a different order in one place is enough to deadlock two checkouts
     * at different tables.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RestaurantTable t where t.qrIdentifier = :qrIdentifier")
    Optional<RestaurantTable> findByQrIdentifierForUpdate(
            @Param("qrIdentifier") String qrIdentifier);

    /** Same lock, by id, for the checkout path which identifies the table that way. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RestaurantTable t where t.id = :id")
    Optional<RestaurantTable> findByIdForUpdate(
            @Param("id") Long id);

    Optional<RestaurantTable> findByIdAndRestaurant(Long id, Restaurant restaurant);

    boolean existsByRestaurantAndTableCode(Restaurant restaurant, String tableCode);

    long countByRestaurantIdAndActiveTrue(Long restaurantId);

    long countByRestaurantId(Long restaurantId);
}
