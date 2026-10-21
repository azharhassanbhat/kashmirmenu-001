package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A cash count for one business day. The expected amount is computed by the server at the moment the
 * count is recorded and stored with it, so the record stays meaningful if payments change later.
 */
@Entity
@Table(name = "cash_reconciliations")
@Getter
@Setter
@NoArgsConstructor
public class CashReconciliation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "expected_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal countedCash;

    /** counted minus expected: negative = short, positive = over. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal difference;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counted_by_user_id")
    private User countedBy;
}
