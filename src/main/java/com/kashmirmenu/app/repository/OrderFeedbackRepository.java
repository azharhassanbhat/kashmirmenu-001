package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.OrderFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderFeedbackRepository extends JpaRepository<OrderFeedback, Long> {

    Optional<OrderFeedback> findByOrderId(Long orderId);

    /** Newest first. The order and its table are fetched with the page (to-one only, so paging stays in the database). */
    @EntityGraph(attributePaths = {"order", "order.restaurantTable"})
    Page<OrderFeedback> findByRestaurantIdOrderByCreatedAtDescIdDesc(Long restaurantId, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.restaurantTable"})
    Page<OrderFeedback> findByRestaurantIdAndRatingLessThanEqualOrderByCreatedAtDescIdDesc(
            Long restaurantId, Integer maxRating, Pageable pageable);

    /** (rating, how many) pairs since a date: the whole summary in one small query. */
    @Query("select f.rating, count(f) from OrderFeedback f "
            + "where f.restaurant.id = :restaurantId and f.createdAt >= :since group by f.rating")
    List<Object[]> countsByRating(@Param("restaurantId") Long restaurantId, @Param("since") LocalDateTime since);
}
