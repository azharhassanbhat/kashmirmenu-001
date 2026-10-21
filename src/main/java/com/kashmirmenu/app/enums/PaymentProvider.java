package com.kashmirmenu.app.enums;

import java.util.Arrays;
import java.util.List;

/**
 * The app/wallet behind a UPI or wallet payment. A payment whose provider was not reliably supplied
 * keeps a null provider and is reported as "Unknown"; it is never inferred from a transaction id.
 * Paytm UPI and Paytm wallet are deliberately separate values.
 */
public enum PaymentProvider {

    GOOGLE_PAY(PaymentMethod.UPI, "Google Pay"),
    PHONEPE(PaymentMethod.UPI, "PhonePe"),
    PAYTM_UPI(PaymentMethod.UPI, "Paytm UPI"),
    BHIM(PaymentMethod.UPI, "BHIM"),
    AMAZON_PAY_UPI(PaymentMethod.UPI, "Amazon Pay UPI"),
    OTHER_UPI(PaymentMethod.UPI, "Other UPI"),

    PAYTM_WALLET(PaymentMethod.WALLET, "Paytm Wallet"),
    AMAZON_PAY_BALANCE(PaymentMethod.WALLET, "Amazon Pay Balance"),
    OTHER_WALLET(PaymentMethod.WALLET, "Other wallet");

    private final PaymentMethod method;
    private final String label;

    PaymentProvider(PaymentMethod method, String label) {
        this.method = method;
        this.label = label;
    }

    /** The parent payment method this provider belongs to. */
    public PaymentMethod method() {
        return method;
    }

    public String label() {
        return label;
    }

    public static List<PaymentProvider> forMethod(PaymentMethod method) {
        return Arrays.stream(values()).filter(p -> p.method == method).toList();
    }
}
