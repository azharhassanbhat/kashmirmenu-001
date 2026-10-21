package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.Settlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository
        extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    /** Every payment of an order, oldest first (an order may have several). */
    List<Payment> findByOrderIdOrderByIdAsc(Long orderId);

    List<Payment> findByRestaurantIdAndIdempotencyKeyIn(Long restaurantId, Collection<String> idempotencyKeys);

    /** Every payment for a batch of orders, so a list of orders needs one query rather than one each. */
    List<Payment> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /** Every payment on a set of orders, with the order and the settlement (if any) it belongs to. */
    @Query("select p from Payment p join fetch p.order left join fetch p.settlement "
            + "where p.order.id in :orderIds order by p.id")
    List<Payment> findForOrdersWithSettlement(@Param("orderIds") Collection<Long> orderIds);

    /** The payments a settlement is made of, with their orders, oldest first. For a batch of settlements. */
    @Query("select p from Payment p join fetch p.order where p.settlement in :settlements order by p.id")
    List<Payment> findBySettlements(@Param("settlements") Collection<Settlement> settlements);

    /** Ids only, so a caller can lock and read each payment fresh instead of trusting a loaded copy. */
    @Query("select p.id from Payment p where p.settlement = :settlement order by p.id")
    List<Long> findIdsBySettlement(@Param("settlement") Settlement settlement);

    /** Row lock so two staff members cannot refund or confirm the same payment at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /** The transaction table: orders and staff are fetched with the page to avoid one query per row. */
    @Override
    @EntityGraph(attributePaths = {"order", "recordedBy"})
    Page<Payment> findAll(Specification<Payment> spec, Pageable pageable);
}
