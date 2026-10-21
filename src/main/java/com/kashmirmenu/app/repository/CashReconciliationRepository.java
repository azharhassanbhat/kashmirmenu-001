package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.CashReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CashReconciliationRepository extends JpaRepository<CashReconciliation, Long> {

    @Query("select c from CashReconciliation c left join fetch c.countedBy where c.restaurant.id = :restaurantId and c.businessDate = :date order by c.id desc")
    List<CashReconciliation> findForDate(@Param("restaurantId") Long restaurantId, @Param("date") LocalDate date);
}
