package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.AnonymousFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnonymousFeedbackRepository extends JpaRepository<AnonymousFeedback, Long> {

    /** One restaurant's landing-page feedback, newest first. Never reaches across restaurants. */
    Page<AnonymousFeedback> findByRestaurantIdOrderByCreatedAtDescIdDesc(Long restaurantId, Pageable pageable);
}
