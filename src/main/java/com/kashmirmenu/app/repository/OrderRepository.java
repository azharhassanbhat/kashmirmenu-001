package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Lookup for the unauthenticated tracking endpoint. */
    Optional<Order> findByPublicToken(String publicToken);

    /** Order numbers repeat across restaurants, so a lookup by number must name the restaurant. */
    Optional<Order> findByOrderNumberAndRestaurant(String orderNumber, Restaurant restaurant);

    /**
     * A page of the restaurant's orders, newest first.
     *
     * <p>Deliberately NOT annotated with an items @EntityGraph. Fetch-joining a collection alongside
     * a Pageable forces Hibernate to apply the limit in memory (HHH90003004) - it reads every row and
     * then discards most of them, which is worse than the N+1 it was meant to fix. The items are
     * loaded for the page's ids by findWithItemsByIdIn instead: two bounded queries, no in-memory
     * paging.
     */
    Page<Order> findByRestaurantOrderByIdDesc(Restaurant restaurant, Pageable pageable);

    /** Loads the items for one page of orders in a single query. */
    @Query("select distinct o from Order o left join fetch o.items i left join fetch i.menuItem where o.id in :ids")
    List<Order> findWithItemsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * A page of one status, newest first. Served by idx_orders_restaurant_status_created.
     *
     * <p>Replaces the unbounded List variant: status=SERVED returned every served order the
     * restaurant had ever taken, and the mapper then loaded each one's items separately. It was the
     * last place in the codebase with the defect fixed for the main listing and the kitchen board.
     */
    Page<Order> findByRestaurantAndStatusOrderByIdDesc(
            Restaurant restaurant,
            OrderStatus status,
            Pageable pageable);

    Optional<Order> findByIdAndRestaurant(Long id, Restaurant restaurant);

    /**
     * Ids only, never entities: a settlement locks each order row and reads it for the first time
     * UNDER that lock, so it cannot act on a copy another transaction has since changed.
     * Cancelled orders are left out; everything else is checked again under the lock.
     */
    @Query("select o.id from Order o where o.dinerSession = :diner "
            + "and o.status <> com.kashmirmenu.app.enums.OrderStatus.CANCELLED order by o.id")
    List<Long> findLockableIdsByDiner(@Param("diner") DinerSession diner);

    @Query("select o.id from Order o where o.tableSession = :visit "
            + "and o.status <> com.kashmirmenu.app.enums.OrderStatus.CANCELLED order by o.id")
    List<Long> findLockableIdsByVisit(@Param("visit") TableSession visit);

    /** Everything one guest has ordered, oldest first: their bill. */
    List<Order> findByDinerSessionOrderByIdAsc(DinerSession dinerSession);

    /** Everything ordered during a batch of table visits, oldest first: one query for a whole list of tables. */
    List<Order> findByTableSessionInOrderByIdAsc(Collection<TableSession> visits);

    /** Everything ordered during one table visit, oldest first: the table's bill. */
    List<Order> findByTableSessionOrderByIdAsc(TableSession tableSession);

    /** The kitchen's pending queue: bounded by status, oldest first so the queue reads in order. */
    List<Order> findByRestaurantAndStatusOrderByCreatedAtAsc(
            Restaurant restaurant,
            OrderStatus status);

    List<Order> findByRestaurantAndStatusInOrderByCreatedAtAsc(
            Restaurant restaurant,
            List<OrderStatus> statuses);

    /** Every order of one service, newest first: what the kitchen's Orders tab lists. */
    @Query("select o from Order o where o.restaurant.id = :restaurantId and o.createdAt >= :since order by o.createdAt desc")
    List<Order> findSince(@Param("restaurantId") Long restaurantId, @Param("since") LocalDateTime since);

    long countByRestaurantIdAndCreatedAtBetween(
            Long restaurantId,
            LocalDateTime start,
            LocalDateTime end
    );

    /**
     * The order, locked, for a staff status change. Two staff members acting on the same order at once (a
     * double click, a kitchen screen and a manager) used to read it unlocked: both then inserted their
     * status-history row, which takes a shared lock on the order, and both then wanted to update it, so MySQL
     * rolled one back with a deadlock that reached the client as an error. Locking first makes the second one
     * wait, then see the new status and be answered by the transition rules instead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.restaurant = :restaurant")
    Optional<Order> findByIdAndRestaurantForUpdate(@Param("id") Long id, @Param("restaurant") com.kashmirmenu.app.entity.Restaurant restaurant);

    /** Row lock used while recording payments so concurrent requests cannot overpay one order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    long countByRestaurantIdAndStatus(
            Long restaurantId,
            OrderStatus status
    );

    long countByRestaurantId(Long restaurantId);
}
