package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Inventory;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.Restaurant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    List<Inventory> findByRestaurant(Restaurant restaurant);

    Optional<Inventory> findByMenuItem(MenuItem menuItem);
    Optional<Inventory> findByMenuItemId(Long menuItemId);

    boolean existsByMenuItem(MenuItem menuItem);

    /**
     * Locks the stock row for the rest of the transaction. Taking stock has to be serialised against
     * other orders for the same dish, or two tables ordering the last portion would both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.menuItem.id = :menuItemId")
    Optional<Inventory> findByMenuItemIdForUpdate(@Param("menuItemId") Long menuItemId);

    List<Inventory> findByRestaurantAndInStockTrue(Restaurant restaurant);

    List<Inventory> findByRestaurantAndAvailableQuantityLessThanEqual(
            Restaurant restaurant,
            Integer quantity);
}