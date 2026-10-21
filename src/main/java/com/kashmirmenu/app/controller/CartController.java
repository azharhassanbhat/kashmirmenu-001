package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.AddCartItemRequest;
import com.kashmirmenu.app.dto.request.CreateCartRequest;
import com.kashmirmenu.app.dto.request.UpdateCartItemRequest;
import com.kashmirmenu.app.dto.response.CartResponse;
import com.kashmirmenu.app.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CHEF','WAITER','CASHIER')")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @PostMapping
    public ResponseEntity<CartResponse> getOrCreateCart(
            @Valid @RequestBody CreateCartRequest request) {

        return ResponseEntity.ok(
                cartService.getOrCreateCart(request)
        );
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @RequestParam String tableQrIdentifier) {

        return ResponseEntity.ok(
                cartService.getCart(tableQrIdentifier, null)
        );
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @Valid @RequestBody AddCartItemRequest request) {

        return ResponseEntity.ok(
                cartService.addItem(request, null)
        );
    }

    @PutMapping("/items/{cartItemId}")
    public ResponseEntity<CartResponse> updateItem(
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemRequest request) {

        return ResponseEntity.ok(
                cartService.updateItem(cartItemId, request, null)
        );
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> removeItem(
            @PathVariable Long cartItemId,
            @RequestParam String tableQrIdentifier) {

        cartService.removeItem(
                cartItemId,
                tableQrIdentifier,
                null
        );

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(
            @RequestParam String tableQrIdentifier) {

        cartService.clearCart(tableQrIdentifier, null);

        return ResponseEntity.noContent().build();
    }
}