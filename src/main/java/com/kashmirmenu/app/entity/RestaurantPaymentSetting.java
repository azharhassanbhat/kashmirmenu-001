package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "restaurant_payment_settings")
@Getter
@Setter
@NoArgsConstructor
public class RestaurantPaymentSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false, unique = true)
    private Restaurant restaurant;

    @Column(name = "cash_enabled", nullable = false)
    private boolean cashEnabled = true;

    @Column(name = "upi_enabled", nullable = false)
    private boolean upiEnabled = false;

    @Column(name = "card_enabled", nullable = false)
    private boolean cardEnabled = false;

    @Column(name = "wallet_enabled", nullable = false)
    private boolean walletEnabled = false;

    @Column(name = "upi_id")
    private String upiId;

    @Column(name = "merchant_name")
    private String merchantName;

    /** Address of the owner's uploaded UPI QR photo (one of our own /api/public/images/ addresses), or null. */
    @Column(name = "upi_qr_url", length = 255)
    private String upiQrUrl;

    public boolean isPaymentMethodEnabled(PaymentMethod paymentMethod) {
        return switch (paymentMethod) {
            case CASH -> cashEnabled;
            case UPI -> upiEnabled;
            case CARD -> cardEnabled;
            case WALLET -> walletEnabled;
            // Counter-only methods (bank transfer, vouchers, ...) have no per-restaurant switch: staff may
            // always record them, and diners are never offered them (see PaymentMethod#isDinerSelectable).
            case BANK_TRANSFER, GIFT_CARD, STORE_CREDIT, VOUCHER, OTHER -> true;
        };
    }
}