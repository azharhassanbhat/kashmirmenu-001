package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.PaymentMethod;

import java.util.List;

public record PublicTableResponse(
        Long restaurantId,
        String restaurantName,
        String logo,
        String primaryColor,
        String secondaryColor,
        String tableName,
        List<PaymentMethod> paymentMethods,
        /* Google "write a review" link; null until the owner sets a Place ID. */
        String googleReviewUrl,
        /* The restaurant's Google Maps page with its reviews; works without an API key. */
        String googleMapsUrl,
        /* The restaurant's own UPI details; null unless UPI is on AND the owner uploaded a QR. */
        UpiPayment upi) {

    /** What a guest needs to pay this restaurant by UPI. Public by design: it is shown on the table's own page. */
    public record UpiPayment(String qrUrl, String upiId, String recipientName) {
    }
}
