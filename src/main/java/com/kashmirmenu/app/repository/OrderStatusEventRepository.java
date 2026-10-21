package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.OrderStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusEventRepository extends JpaRepository<OrderStatusEvent, Long> {

    List<OrderStatusEvent> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);
}
