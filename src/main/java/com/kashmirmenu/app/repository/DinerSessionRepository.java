package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.TableSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DinerSessionRepository extends JpaRepository<DinerSession, Long> {

    List<DinerSession> findByTableSessionOrderByJoinNumberAsc(TableSession tableSession);

    Optional<DinerSession> findByTokenHash(String tokenHash);

    /** Per visit: [visit id, number of guests, when a guest last acted]. One query for a whole list of tables. */
    @Query("select d.tableSession.id, count(d), max(d.lastSeenAt) from DinerSession d "
            + "where d.tableSession in :visits group by d.tableSession.id")
    List<Object[]> activityOf(@Param("visits") Collection<TableSession> visits);

    /**
     * Locks one guest's row for the rest of the transaction. Every cart operation and checkout by a
     * guest starts here, so a double-tap (two requests from one phone) is serialised, while two
     * different guests at the same table do not wait for each other.
     *
     * <p>Deliberately no fetch join: with a pessimistic lock Hibernate would lock the joined
     * restaurant_tables row too, which would serialise the whole table again and break the lock
     * order documented on RestaurantTableRepository.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DinerSession d where d.id = :id")
    Optional<DinerSession> findByIdForUpdate(@Param("id") Long id);

    /** A guest's session, only while its visit is still OPEN. Fetches what the caller checks. */
    @Query("select d from DinerSession d "
            + "join fetch d.tableSession ts "
            + "join fetch ts.restaurantTable "
            + "where d.tokenHash = :tokenHash "
            + "and ts.status = com.kashmirmenu.app.enums.TableSessionStatus.OPEN")
    Optional<DinerSession> findOpenByTokenHash(@Param("tokenHash") String tokenHash);
}
