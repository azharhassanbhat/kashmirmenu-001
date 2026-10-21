package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.PaymentRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    @Query("select r from PaymentRefund r left join fetch r.refundedBy where r.payment.id = :paymentId order by r.refundedAt asc, r.id asc")
    List<PaymentRefund> findByPaymentId(@Param("paymentId") Long paymentId);

    /** Total refunded so far for one payment (zero when none). */
    @Query("select coalesce(sum(r.amount), 0) from PaymentRefund r where r.payment.id = :paymentId")
    BigDecimal sumByPaymentId(@Param("paymentId") Long paymentId);

    /** Rows of [paymentId, totalRefunded] for a batch of payments, in one query. */
    @Query("select r.payment.id, sum(r.amount) from PaymentRefund r where r.payment.id in :paymentIds group by r.payment.id")
    List<Object[]> sumByPaymentIds(@Param("paymentIds") Collection<Long> paymentIds);
}
