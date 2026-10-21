package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.ApplyCouponRequest;
import com.kashmirmenu.app.dto.request.CreateCouponRequest;
import com.kashmirmenu.app.dto.request.UpdateCouponRequest;
import com.kashmirmenu.app.dto.response.ApplyCouponResponse;
import com.kashmirmenu.app.dto.response.CouponResponse;
import com.kashmirmenu.app.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/coupons")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    public ResponseEntity<CouponResponse> createCoupon(
            @Valid @RequestBody CreateCouponRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(couponService.createCoupon(request));
    }

    @GetMapping
    public ResponseEntity<List<CouponResponse>> getAllCoupons(
            @RequestParam Long restaurantId) {

        return ResponseEntity.ok(
                couponService.getAllCoupons(restaurantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CouponResponse> getCouponById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                couponService.getCouponById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CouponResponse> updateCoupon(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCouponRequest request) {

        return ResponseEntity.ok(
                couponService.updateCoupon(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCoupon(
            @PathVariable Long id) {

        couponService.deleteCoupon(id);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply")
    public ResponseEntity<ApplyCouponResponse> applyCoupon(
            @Valid @RequestBody ApplyCouponRequest request) {

        return ResponseEntity.ok(
                couponService.applyCoupon(request));
    }
}