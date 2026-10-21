package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.ServiceRequestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row of a request's append-only history. Never updated or deleted by the application. */
@Entity
@Table(name = "service_request_events")
@Getter
@Setter
@NoArgsConstructor
public class ServiceRequestEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_request_id", nullable = false, updatable = false)
    private ServiceRequest serviceRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private ServiceRequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private ServiceRequestStatus toStatus;

    @Column(name = "actor_label", nullable = false, length = 120, updatable = false)
    private String actorLabel;
}
