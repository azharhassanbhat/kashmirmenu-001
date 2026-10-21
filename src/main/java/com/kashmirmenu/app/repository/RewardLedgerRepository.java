package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.RewardLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardLedgerRepository extends JpaRepository<RewardLedger, Long> {

    boolean existsByOrderId(Long orderId);

    List<RewardLedger> findTop10ByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);
}
