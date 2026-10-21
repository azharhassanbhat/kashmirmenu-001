package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A restaurant's own service charge and tax rules. No rate is built in: a restaurant with no row, or
 * with zeros, charges neither. See V36.
 */
@Entity
@Table(
        name = "restaurant_billing_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_billing_settings_restaurant", columnNames = "restaurant_id"))
@Getter
@Setter
@NoArgsConstructor
public class RestaurantBillingSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @Column(name = "tax_name", nullable = false, length = 30)
    private String taxName = "Tax";

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "service_charge_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal serviceChargeRate = BigDecimal.ZERO;

    @Column(name = "prices_include_tax", nullable = false)
    private Boolean pricesIncludeTax = false;

    /** Printed on receipts as entered (tidied, never verified). null means none. See {@link com.kashmirmenu.app.service.TaxRegistration}. */
    @Column(name = "tax_registration_number", length = 30)
    private String taxRegistrationNumber;
}
