package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrder(Order order);

    /**
     * Units sold per dish since a cut-off, cancelled orders excluded: the real order data behind the
     * "Popular" badge. Rows are {menuItemId, units}.
     */
    @Query("select oi.menuItem.id, sum(oi.quantity) from OrderItem oi "
            + "where oi.order.restaurant.id = :restaurantId "
            + "and oi.order.status <> com.kashmirmenu.app.enums.OrderStatus.CANCELLED "
            + "and oi.order.createdAt >= :since group by oi.menuItem.id")
    List<Object[]> unitsSoldSince(
            @Param("restaurantId") Long restaurantId,
            @Param("since") LocalDateTime since);
}