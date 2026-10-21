package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_email",
                        columnNames = "email"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(nullable = false)
    private String password;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "role_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_role")
    )
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "restaurant_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_user_restaurant")
    )
    private Restaurant restaurant;

    /**
     * Hash of the single-use token that lets an unclaimed platform-owner account set its first
     * password. Null on every ordinary account, and nulled the moment it is used.
     */
    @Column(name = "platform_claim_token_hash", length = 100)
    private String platformClaimTokenHash;

    @Column(name = "platform_claim_expires_at")
    private LocalDateTime platformClaimExpiresAt;

    /**
     * Tokens issued before this instant are rejected. Set on logout and on password change, which
     * gives real revocation without server-side sessions. Null means "no revocation yet".
     */
    @Column(name = "tokens_valid_from")
    private LocalDateTime tokensValidFrom;
}