package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType;

    /** Structured meaning of the event; null on rows saved before it existed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", length = 40)
    private NotificationEventKind eventKind;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean read = false;
}