package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Cart;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.enums.CartStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long>,
        JpaSpecificationExecutor<Cart> {

    /** A guest's own cart. Callers hold that guest's row lock first (DinerSessionRepository.findByIdForUpdate). */
    Optional<Cart> findByDinerSessionAndStatus(
            DinerSession dinerSession,
            CartStatus status);

    /**
     * The table's STAFF cart: the one with no guest. Deliberately not "the table's cart" any more -
     * a table now has one active cart per guest, so a lookup by table alone would match several.
     */
    Optional<Cart> findByRestaurantTableAndStatusAndDinerSessionIsNull(
            RestaurantTable restaurantTable,
            CartStatus status);

    Optional<Cart> findByIdAndRestaurant(Long id, Restaurant restaurant);

    /**
     * Closing a table withdraws the guests' unsubmitted carts: their tokens stop working, so nobody can
     * reach them again. Orders already placed are untouched.
     */
    @Modifying
    @Query("update Cart c set c.status = com.kashmirmenu.app.enums.CartStatus.CANCELLED "
            + "where c.tableSession = :visit and c.status = com.kashmirmenu.app.enums.CartStatus.ACTIVE")
    int cancelActiveCarts(@Param("visit") TableSession visit);
}