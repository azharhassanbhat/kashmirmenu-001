package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.ServiceRequestEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceRequestEventRepository extends JpaRepository<ServiceRequestEvent, Long> {

    List<ServiceRequestEvent> findByServiceRequestIdOrderByCreatedAtAscIdAsc(Long serviceRequestId);
}
