package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.ServiceRequest;
import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {

    boolean existsByRequestNumber(String requestNumber);

    /** The open request of this type at this visit, if any: what a repeat tap should return instead of a second one. */
    Optional<ServiceRequest> findFirstByTableSessionIdAndTypeAndStatusIn(
            Long tableSessionId, ServiceRequestType type, Collection<ServiceRequestStatus> statuses);

    long countByTableSessionIdAndStatusIn(Long tableSessionId, Collection<ServiceRequestStatus> statuses);

    long countByTableSessionIdAndTypeAndStatusIn(
            Long tableSessionId, ServiceRequestType type, Collection<ServiceRequestStatus> statuses);

    /** Everything the staff board needs to draw a card, in one query (no per-row table/diner lookups). */
    @EntityGraph(attributePaths = {"restaurantTable", "dinerSession", "assignedTo"})
    @Query("select r from ServiceRequest r where r.restaurant.id = :restaurantId "
            + "and r.status in :statuses order by r.createdAt asc")
    List<ServiceRequest> findForBoard(
            @Param("restaurantId") Long restaurantId,
            @Param("statuses") Collection<ServiceRequestStatus> statuses);

    /** Finished requests since a cut-off, newest first, for the board's "done" column. */
    @EntityGraph(attributePaths = {"restaurantTable", "dinerSession", "assignedTo"})
    @Query("select r from ServiceRequest r where r.restaurant.id = :restaurantId "
            + "and r.status in :statuses and r.updatedAt >= :since order by r.updatedAt desc")
    List<ServiceRequest> findFinishedSince(
            @Param("restaurantId") Long restaurantId,
            @Param("statuses") Collection<ServiceRequestStatus> statuses,
            @Param("since") LocalDateTime since);

    /** A guest's view: every request at this visit, newest first. */
    @EntityGraph(attributePaths = {"restaurantTable", "dinerSession", "assignedTo"})
    @Query("select r from ServiceRequest r where r.tableSession.id = :tableSessionId order by r.createdAt desc")
    List<ServiceRequest> findForVisit(@Param("tableSessionId") Long tableSessionId);

    @EntityGraph(attributePaths = {"restaurantTable", "dinerSession", "assignedTo"})
    @Query("select r from ServiceRequest r where r.id = :id and r.restaurant.id = :restaurantId")
    Optional<ServiceRequest> findByIdAndRestaurantId(
            @Param("id") Long id, @Param("restaurantId") Long restaurantId);

    /** Row lock for status changes, so two staff tapping at once cannot both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.id = :id and r.restaurant.id = :restaurantId")
    Optional<ServiceRequest> lockByIdAndRestaurantId(
            @Param("id") Long id, @Param("restaurantId") Long restaurantId);

    @EntityGraph(attributePaths = {"restaurantTable", "dinerSession", "assignedTo"})
    @Query("select r from ServiceRequest r where r.requestNumber = :requestNumber "
            + "and r.tableSession.id = :tableSessionId")
    Optional<ServiceRequest> findByNumberInVisit(
            @Param("requestNumber") String requestNumber, @Param("tableSessionId") Long tableSessionId);
}
