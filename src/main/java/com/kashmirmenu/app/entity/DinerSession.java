package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One guest within a table visit: a name and a secret only they hold. No account or login.
 *
 * <p>Only a SHA-256 hash of the secret is stored, so a database read cannot be turned into a
 * working session. The secret is 256 random bits, which is why a plain, unsalted hash is enough.
 * Identity is the secret, never the name: two guests may both be called Rahul.
 */
@Entity
@Table(
        name = "diner_sessions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_diner_sessions_token_hash", columnNames = "token_hash"),
                @UniqueConstraint(
                        name = "uk_diner_sessions_join",
                        columnNames = {"table_session_id", "join_number"})
        })
@Getter
@Setter
@NoArgsConstructor
public class DinerSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "table_session_id", nullable = false, updatable = false)
    private TableSession tableSession;

    /** What staff and other guests see. Made unique within the visit ("Rahul", "Rahul (2)"). */
    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    /** 1 for the first guest of the visit, 2 for the next, and so on. */
    @Column(name = "join_number", nullable = false, updatable = false)
    private Integer joinNumber;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;
}
