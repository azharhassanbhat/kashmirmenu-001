package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.analytics.DateRange;
import com.kashmirmenu.app.analytics.HourlyFact;
import com.kashmirmenu.app.analytics.PaymentFilter;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.response.DashboardSummaryResponse;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.repository.NotificationRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.PaymentAnalyticsQueries;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final CurrentUserService currentUserService;
    private final OrderRepository orderRepository;
    private final PaymentAnalyticsQueries paymentQueries;
    private final NotificationRepository notificationRepository;
    private final BusinessTime businessTime;

    @Override
    @Transactional(readOnly = true)
    public DashboardSummaryResponse getDashboardSummary() {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        LocalDate today = businessTime.today();

        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.atTime(LocalTime.MAX);

        // Same definition as the Payments & Revenue page: money received today minus refunds issued today.
        DateRange day = new DateRange(start, today.plusDays(1).atStartOfDay());
        PaymentFilter everything = PaymentFilter.of(restaurantId, null, null, null, null);
        BigDecimal todayRevenue = total(paymentQueries.collected(everything, day))
                .subtract(total(paymentQueries.refunds(everything, day)));

        Long todayOrders = orderRepository.countByRestaurantIdAndCreatedAtBetween(
                restaurantId,
                start,
                end
        );

        Long pendingOrders = orderRepository.countByRestaurantIdAndStatus(
                restaurantId,
                OrderStatus.PENDING
        );

        Long preparingOrders = orderRepository.countByRestaurantIdAndStatus(
                restaurantId,
                OrderStatus.PREPARING
        );

        Long readyOrders = orderRepository.countByRestaurantIdAndStatus(
                restaurantId,
                OrderStatus.READY
        );

        Long servedOrders = orderRepository.countByRestaurantIdAndStatus(
                restaurantId,
                OrderStatus.SERVED
        );

        Long unreadNotifications = notificationRepository.countByRestaurantIdAndReadFalse(
                restaurantId
        );

        return DashboardSummaryResponse.builder()
                .todayRevenue(todayRevenue)
                .todayOrders(todayOrders)
                .pendingOrders(pendingOrders)
                .preparingOrders(preparingOrders)
                .readyOrders(readyOrders)
                .servedOrders(servedOrders)
                .unreadNotifications(unreadNotifications)
                .build();
    }

    private static BigDecimal total(List<HourlyFact> facts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (HourlyFact f : facts) {
            if (f.amount() != null) {
                sum = sum.add(f.amount());
            }
        }
        return sum;
    }
}
