package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One restaurant's QR landing page: branding, links, hours, guest Wi-Fi, which actions show, and rewards rules.
 * Optional: a restaurant with no row gets the defaults. See V41.
 *
 * <p>The Wi-Fi password is deliberately NOT part of any admin read response; only the guest Wi-Fi endpoint (scoped
 * to a table's QR) ever returns it.
 */
@Entity
@Table(
        name = "restaurant_landing_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_landing_restaurant", columnNames = "restaurant_id"))
@Getter
@Setter
@NoArgsConstructor
public class RestaurantLandingSettings extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @Column(name = "landing_enabled", nullable = false)
    private Boolean landingEnabled = true;

    @Column(length = 120)
    private String tagline;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(length = 300)
    private String announcement;

    @Column(name = "opening_hours", length = 700)
    private String openingHours;

    @Column(name = "google_review_url", length = 500)
    private String googleReviewUrl;

    @Column(name = "instagram_url", length = 500)
    private String instagramUrl;

    @Column(name = "youtube_url", length = 500)
    private String youtubeUrl;

    @Column(name = "facebook_url", length = 500)
    private String facebookUrl;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Column(name = "map_url", length = 500)
    private String mapUrl;

    @Column(name = "wifi_enabled", nullable = false)
    private Boolean wifiEnabled = false;

    @Column(name = "wifi_ssid", length = 64)
    private String wifiSsid;

    @Column(name = "wifi_security", nullable = false, length = 8)
    private String wifiSecurity = "WPA";

    @Column(name = "wifi_password", length = 63)
    private String wifiPassword;

    @Column(name = "feedback_enabled", nullable = false)
    private Boolean feedbackEnabled = true;

    @Column(name = "assistance_enabled", nullable = false)
    private Boolean assistanceEnabled = true;

    @Column(name = "rewards_enabled", nullable = false)
    private Boolean rewardsEnabled = false;

    @Column(name = "rewards_spend_per_point", nullable = false, precision = 10, scale = 2)
    private BigDecimal rewardsSpendPerPoint = new BigDecimal("10.00");

    @Column(name = "rewards_redeem_points", nullable = false)
    private Integer rewardsRedeemPoints = 100;

    @Column(name = "rewards_reward_text", length = 150)
    private String rewardsRewardText;

    @Column(name = "rewards_terms", length = 300)
    private String rewardsTerms;

    @Column(name = "action_order", length = 100)
    private String actionOrder;
}
