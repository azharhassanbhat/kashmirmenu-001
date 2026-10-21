package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "restaurant_tables",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_restaurant_table_code",
                        columnNames = {"restaurant_id", "table_code"}
                ),
                @UniqueConstraint(
                        name = "uk_restaurant_table_qr_identifier",
                        columnNames = "qr_identifier"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestaurantTable extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(name = "table_code", nullable = false, length = 30)
    private String tableCode;

    @Column(name = "table_name", nullable = false, length = 100)
    private String tableName;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "qr_identifier", nullable = false, unique = true, length = 36)
    private String qrIdentifier;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @PrePersist
    public void prePersist() {
        if (qrIdentifier == null || qrIdentifier.isBlank()) {
            qrIdentifier = UUID.randomUUID().toString();
        }
    }
}