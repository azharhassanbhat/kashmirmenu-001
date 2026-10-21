package com.kashmirmenu.app.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummaryResponse {

    private BigDecimal todayRevenue;

    private Long todayOrders;

    private Long pendingOrders;

    private Long preparingOrders;

    private Long readyOrders;

    private Long servedOrders;

    private Long unreadNotifications;
}