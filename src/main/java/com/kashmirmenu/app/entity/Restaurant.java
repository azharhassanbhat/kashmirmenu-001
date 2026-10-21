package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.RestaurantStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(
        name = "restaurants",
        indexes = {
                @Index(name = "idx_restaurant_slug", columnList = "slug"),
                @Index(name = "idx_restaurant_active", columnList = "active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 100)
    private String country;

    @Column(length = 255)
    private String logo;

    @Column(length = 20)
    private String primaryColor;

    @Column(length = 20)
    private String secondaryColor;

    /** Google Place ID used for review links and rating display; optional. */
    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    /**
     * The platform owner's decision. Defaults to APPROVED so every code path that creates a
     * restaurant other than registration behaves as it always did; registration sets the status
     * explicitly (see RestaurantApprovalProperties).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private RestaurantStatus approvalStatus = RestaurantStatus.APPROVED;

    /** Platform owner who made the latest decision; null while still PENDING. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(name = "reviewed_at", columnDefinition = "DATETIME")
    private LocalDateTime reviewedAt;

    /** Why the registration was declined. Kept after a later approval only if never cleared. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;
}