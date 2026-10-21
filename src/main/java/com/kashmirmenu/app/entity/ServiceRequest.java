package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A guest's table-side request (water, waiter, bill, ...). See V34 for the lifecycle and the
 * database-level guard against duplicate open requests.
 */
@Entity
@Table(
        name = "service_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_service_requests_number", columnNames = "request_number"))
@Getter
@Setter
@NoArgsConstructor
public class ServiceRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_number", nullable = false, length = 20, updatable = false)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_table_id", nullable = false, updatable = false)
    private RestaurantTable restaurantTable;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "table_session_id", nullable = false, updatable = false)
    private TableSession tableSession;

    /** The guest who raised it; null only if a future channel (e.g. staff-entered) has no guest. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diner_session_id", updatable = false)
    private DinerSession dinerSession;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30, updatable = false)
    private ServiceRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceRequestStatus status = ServiceRequestStatus.PENDING;

    @Column(length = 200)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_user_id")
    private User assignedTo;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
