package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.TableSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TableSessionRepository extends JpaRepository<TableSession, Long> {

    /**
     * The table's open visit, if any. Callers that may create one must hold the table row's lock
     * first (RestaurantTableRepository.findByQrIdentifierForUpdate): this query alone cannot stop
     * two requests from both finding nothing and both inserting.
     */
    Optional<TableSession> findByRestaurantTableAndStatus(
            RestaurantTable restaurantTable, TableSessionStatus status);

    Optional<TableSession> findByIdAndRestaurant(Long id, Restaurant restaurant);

    /**
     * Just the id of a visit's table, scoped to the restaurant. Lets a caller lock the table row before
     * the visit is loaded at all, so the visit's first read happens under its own lock.
     */
    @Query("select t.restaurantTable.id from TableSession t where t.id = :id and t.restaurant = :restaurant")
    Optional<Long> findTableIdOf(@Param("id") Long id, @Param("restaurant") Restaurant restaurant);

    /** Row lock, for closing and reopening: they must not interleave with each other or with idle expiry. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableSession t where t.id = :id")
    Optional<TableSession> findByIdForUpdate(@Param("id") Long id);

    /** A restaurant's visits in one state, with their tables, oldest first. */
    @Query("select t from TableSession t join fetch t.restaurantTable "
            + "where t.restaurant = :restaurant and t.status = :status order by t.id")
    List<TableSession> findWithTable(
            @Param("restaurant") Restaurant restaurant, @Param("status") TableSessionStatus status);

    /** Visits closed since a time, newest first: where a table closed by mistake is found again. */
    @Query("select t from TableSession t join fetch t.restaurantTable "
            + "where t.restaurant = :restaurant and t.status = com.kashmirmenu.app.enums.TableSessionStatus.CLOSED "
            + "and t.closedAt >= :since order by t.closedAt desc")
    List<TableSession> findClosedSince(
            @Param("restaurant") Restaurant restaurant, @Param("since") LocalDateTime since, Pageable pageable);

    /** Open visits in which no guest has done anything since the cutoff. Ids only; each is re-checked under its lock. */
    @Query("select t.id from TableSession t where t.status = com.kashmirmenu.app.enums.TableSessionStatus.OPEN "
            + "and not exists (select 1 from DinerSession d where d.tableSession = t and d.lastSeenAt > :cutoff)")
    List<Long> findIdleIds(@Param("cutoff") LocalDateTime cutoff);
}
