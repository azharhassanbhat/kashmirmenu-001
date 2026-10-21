package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.service.ReceiptService;
import com.kashmirmenu.app.dto.response.ReceiptResponse;
import com.kashmirmenu.app.service.FeedbackRules;
import com.kashmirmenu.app.service.FeedbackService;
import com.kashmirmenu.app.dto.response.FeedbackResponse;
import com.kashmirmenu.app.dto.request.FeedbackRequest;
import com.kashmirmenu.app.dto.request.CreatePaymentRequest;
import com.kashmirmenu.app.dto.request.PublicCartItemRequest;
import com.kashmirmenu.app.dto.request.PublicCouponRequest;
import com.kashmirmenu.app.dto.request.PublicOrderRequest;
import com.kashmirmenu.app.dto.request.PublicQuantityRequest;
import com.kashmirmenu.app.dto.response.CartResponse;
import com.kashmirmenu.app.dto.response.OrderResponse;
import com.kashmirmenu.app.dto.response.OrderTrackingResponse;
import com.kashmirmenu.app.dto.response.PaymentResponse;
import com.kashmirmenu.app.dto.response.PublicMenuCategoryResponse;
import com.kashmirmenu.app.dto.response.PublicReviewsResponse;
import com.kashmirmenu.app.dto.response.PublicTableResponse;
import com.kashmirmenu.app.service.PublicOrderingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicOrderingService publicOrderingService;
    private final FeedbackService feedbackService;
    private final ReceiptService receiptService;

    @Operation(summary = "Table, restaurant branding and enabled payment methods")
    @GetMapping("/tables/{qr}")
    public PublicTableResponse getTable(@PathVariable String qr) {
        return publicOrderingService.getTable(qr);
    }

    @Operation(summary = "Customer-facing menu grouped by category")
    @GetMapping("/tables/{qr}/menu")
    public List<PublicMenuCategoryResponse> getMenu(@PathVariable String qr) {
        return publicOrderingService.getMenu(qr);
    }

    @Operation(summary = "Google rating, recent reviews and the write-a-review link")
    @GetMapping("/tables/{qr}/reviews")
    public PublicReviewsResponse getReviews(@PathVariable String qr) {
        return publicOrderingService.getReviews(qr);
    }

    // The cart and order endpoints act as one guest, identified by the X-Diner-Token header from
    // POST /tables/{qr}/join. Without a valid token they answer 404 "session has ended", so the client
    // knows to ask for a name again. A guest only ever sees and changes their own cart.
    private static final String TOKEN_HEADER = PublicDinerController.TOKEN_HEADER;

    @Operation(summary = "The calling guest's own cart")
    @GetMapping("/tables/{qr}/cart")
    public CartResponse getCart(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        return publicOrderingService.getCart(qr, dinerToken);
    }

    @PostMapping("/tables/{qr}/cart/items")
    public CartResponse addCartItem(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @Valid @RequestBody PublicCartItemRequest request) {
        return publicOrderingService.addCartItem(qr, dinerToken, request);
    }

    @PutMapping("/tables/{qr}/cart/items/{cartItemId}")
    public CartResponse updateCartItem(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @PathVariable Long cartItemId,
            @Valid @RequestBody PublicQuantityRequest request) {
        return publicOrderingService.updateCartItem(
                qr, dinerToken, cartItemId, request.quantity());
    }

    @DeleteMapping("/tables/{qr}/cart/items/{cartItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCartItem(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @PathVariable Long cartItemId) {
        publicOrderingService.removeCartItem(qr, dinerToken, cartItemId);
    }

    @DeleteMapping("/tables/{qr}/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCart(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        publicOrderingService.clearCart(qr, dinerToken);
    }

    @Operation(summary = "Apply a coupon code to the calling guest's cart")
    @PostMapping("/tables/{qr}/cart/coupon")
    public CartResponse applyCoupon(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @Valid @RequestBody PublicCouponRequest request) {
        return publicOrderingService.applyCoupon(qr, dinerToken, request.couponCode());
    }

    @Operation(summary = "Remove the coupon from the calling guest's cart")
    @DeleteMapping("/tables/{qr}/cart/coupon")
    public CartResponse removeCoupon(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        return publicOrderingService.removeCoupon(qr, dinerToken);
    }

    @Operation(summary = "Place an order from the calling guest's own cart")
    @PostMapping("/tables/{qr}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @Valid @RequestBody(required = false) PublicOrderRequest request) {
        return publicOrderingService.placeOrder(
                qr, dinerToken, request == null ? null : request.notes());
    }

    // Keyed by the order's random public token, not its number. These two endpoints are
    // unauthenticated, so the key in the URL is the only thing protecting one diner's order from
    // another - and order numbers are short and sequential by design, for readability.
    @Operation(summary = "Track an order by its public token")
    @GetMapping("/orders/by-token/{token}")
    public OrderTrackingResponse trackOrder(@PathVariable String token) {
        return publicOrderingService.trackOrder(token);
    }

    // Same credential as tracking: the order's random token. It can rate that one order, once served, and
    // sending again replaces the earlier rating rather than adding a second.
    @Operation(summary = "Rate a served order (private: only the restaurant's management sees it)")
    @PutMapping("/orders/by-token/{token}/feedback")
    public FeedbackResponse sendFeedback(@PathVariable String token, @RequestBody FeedbackRequest request) {
        return feedbackService.submit(token, FeedbackRules.wholeRating(request.rating()), request.comment());
    }

    // Same credential as tracking: the order's random token. Read-only, and never includes guest details.
    @Operation(summary = "A printable receipt for an order (not available for a cancelled order)")
    @GetMapping("/orders/by-token/{token}/receipt")
    public ReceiptResponse receipt(@PathVariable String token) {
        return receiptService.forToken(token);
    }

    @Operation(summary = "Choose a payment method for an order (creates a PENDING payment)")
    @PostMapping("/orders/by-token/{token}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse createPayment(
            @PathVariable String token,
            @Valid @RequestBody CreatePaymentRequest request) {
        return publicOrderingService.createPayment(token, request);
    }
}
