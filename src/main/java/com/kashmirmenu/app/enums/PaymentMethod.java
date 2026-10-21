package com.kashmirmenu.app.enums;

/**
 * How money was tendered. UPI and WALLET have a second level ({@link PaymentProvider}: Google Pay,
 * PhonePe, Paytm wallet, ...). Adding a method here is the only code change needed for it to show up
 * in the analytics: labels, colours and grouping are served to the dashboard from this definition.
 */
public enum PaymentMethod {

    CASH("Cash", true, false, true),
    UPI("UPI", false, true, true),
    CARD("Card", false, true, true),
    WALLET("Wallet", false, true, true),

    // Recorded by staff at the counter only; never offered to diners on the QR page.
    BANK_TRANSFER("Bank transfer", false, true, false),
    GIFT_CARD("Gift card", false, false, false),
    STORE_CREDIT("Restaurant credit", false, false, false),
    VOUCHER("Voucher", false, false, false),
    OTHER("Other", false, false, false);

    private final String label;
    private final boolean cash;
    private final boolean digital;
    private final boolean dinerSelectable;

    PaymentMethod(String label, boolean cash, boolean digital, boolean dinerSelectable) {
        this.label = label;
        this.cash = cash;
        this.digital = digital;
        this.dinerSelectable = dinerSelectable;
    }

    public String label() {
        return label;
    }

    public boolean isCash() {
        return cash;
    }

    /** Electronic money that settles through a bank/gateway (UPI, card, wallet, bank transfer). */
    public boolean isDigital() {
        return digital;
    }

    /** Whether a diner may pick it on the public order page. */
    public boolean isDinerSelectable() {
        return dinerSelectable;
    }

    /** Methods whose payments can carry a provider (app/wallet). */
    public boolean hasProviders() {
        return this == UPI || this == WALLET;
    }
}
