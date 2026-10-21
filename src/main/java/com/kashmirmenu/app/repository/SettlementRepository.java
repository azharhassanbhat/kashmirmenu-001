package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.Settlement;
import com.kashmirmenu.app.entity.TableSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByCode(String code);

    /** A retried request: the settlement this guest already made with this key. */
    Optional<Settlement> findByPayerAndIdempotencyKey(DinerSession payer, String idempotencyKey);

    /** Everything paid, or being paid, during one visit, newest first: the staff's payment history for a table. */
    List<Settlement> findByTableSessionOrderByIdDesc(TableSession tableSession);

    /** A guest's own settlements, newest first. */
    List<Settlement> findByPayerOrderByIdDesc(DinerSession payer);

    /** Settlements that still have a payment waiting for staff, oldest first: what the counter must act on. */
    @Query("select s from Settlement s where s.restaurant = :restaurant and exists ("
            + "select 1 from Payment p where p.settlement = s "
            + "and p.paymentStatus = com.kashmirmenu.app.enums.PaymentStatus.PENDING) order by s.id")
    List<Settlement> findAwaitingStaff(@Param("restaurant") Restaurant restaurant);
}
