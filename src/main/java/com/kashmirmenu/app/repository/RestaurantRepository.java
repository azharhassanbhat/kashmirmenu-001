package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import com.kashmirmenu.app.enums.RestaurantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>,
        JpaSpecificationExecutor<Restaurant> {

    boolean existsBySlug(String slug);

    Optional<Restaurant> findBySlug(String slug);

    Optional<Restaurant> findByIdAndActiveTrue(Long id);

    Page<Restaurant> findByActiveTrue(Pageable pageable);

    Page<Restaurant> findByActiveFalse(Pageable pageable);

    Optional<Restaurant> findByOwner(User owner);

    /**
     * Row-locks the restaurant so two platform owners deciding at once are serialised: the second
     * sees the first's outcome and is refused as an invalid transition instead of overwriting it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Restaurant r where r.id = :id")
    Optional<Restaurant> findByIdForUpdate(@Param("id") Long id);

    long countByApprovalStatus(RestaurantStatus status);

    /** Cheap status read for the request gate; avoids loading the entity on every request. */
    @Query("select r.approvalStatus from Restaurant r where r.id = :id")
    Optional<RestaurantStatus> findApprovalStatusById(@Param("id") Long id);

    /** Status of the restaurant a table QR belongs to, for the public QR flow. */
    @Query("select t.restaurant.approvalStatus from RestaurantTable t where t.qrIdentifier = :qr")
    Optional<RestaurantStatus> findApprovalStatusByTableQr(@Param("qr") String qr);
}
