package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything printed on a receipt. The amounts are the ones RECORDED on the order when it was placed (the
 * prices, rates and totals the guest was shown), never recalculated, so a receipt cannot disagree with the
 * bill. The charge field names match the guest's order page so the two describe a bill identically.
 *
 * <p>Deliberately holds no guest name, phone or feedback.
 *
 * @param paymentHeading PAID, PART PAID, UNPAID, REFUNDED or NO PAYMENT DUE
 * @param amountPaid     received and not refunded
 */
public record ReceiptResponse(
        Restaurant restaurant,
        String orderNumber,
        String tableName,
        LocalDateTime placedAt,
        List<Item> items,
        BigDecimal subtotal,
        BigDecimal discount,
        String couponCode,
        BigDecimal serviceCharge,
        BigDecimal serviceChargeRate,
        BigDecimal tax,
        String taxName,
        BigDecimal taxRate,
        BigDecimal taxIncluded,
        boolean pricesIncludeTax,
        BigDecimal total,
        List<Payment> payments,
        BigDecimal amountPaid,
        BigDecimal refunded,
        BigDecimal balanceDue,
        String paymentHeading) {

    /** @param taxRegistrationNumber printed as the owner entered it; not verified by the application */
    public record Restaurant(String name, String address, String city, String state, String phone,
                             String taxRegistrationNumber) {
    }

    /** @param options chosen options as "Group: Option"; their prices are already inside {@code unitPrice} */
    public record Item(String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal, List<String> options) {
    }

    /** One payment actually received, described as it should read ("Cash", "UPI (Google Pay)"). */
    public record Payment(String method, BigDecimal amount, LocalDateTime paidAt) {
    }
}
