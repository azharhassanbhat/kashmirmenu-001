package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.ApplyCouponRequest;
import com.kashmirmenu.app.dto.request.CreateCouponRequest;
import com.kashmirmenu.app.dto.request.UpdateCouponRequest;
import com.kashmirmenu.app.dto.response.ApplyCouponResponse;
import com.kashmirmenu.app.dto.response.CouponResponse;

import java.util.List;

public interface CouponService {

    CouponResponse createCoupon(CreateCouponRequest request);

    List<CouponResponse> getAllCoupons(Long restaurantId);

    CouponResponse getCouponById(Long id);

    CouponResponse updateCoupon(Long id, UpdateCouponRequest request);

    void deleteCoupon(Long id);

    ApplyCouponResponse applyCoupon(ApplyCouponRequest request);
}