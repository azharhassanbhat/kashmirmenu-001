package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.dto.request.ApplyCouponRequest;
import com.kashmirmenu.app.dto.request.CreateCouponRequest;
import com.kashmirmenu.app.dto.request.UpdateCouponRequest;
import com.kashmirmenu.app.dto.response.ApplyCouponResponse;
import com.kashmirmenu.app.dto.response.CouponResponse;
import com.kashmirmenu.app.entity.Coupon;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.coupon.CouponCode;
import com.kashmirmenu.app.coupon.CouponPricing;
import com.kashmirmenu.app.coupon.CouponTerms;
import com.kashmirmenu.app.coupon.CouponState;
import com.kashmirmenu.app.enums.DiscountType;
import com.kashmirmenu.app.exception.AccessDeniedException;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.mapper.CouponMapper;
import com.kashmirmenu.app.repository.CouponRepository;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CouponServiceImpl implements CouponService {

    private final CouponRepository couponRepository;
    private final RestaurantRepository restaurantRepository;
    private final CouponMapper couponMapper;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;

    @Override
    public CouponResponse createCoupon(CreateCouponRequest request) {

        Restaurant restaurant = requireOwnRestaurant(request.getRestaurantId());

        String code = CouponCode.normalise(request.getCode());

        if (couponRepository.existsByRestaurantAndCode(restaurant, code)) {
            throw new IllegalArgumentException("Coupon code already exists.");
        }

        if (request.getStartDate().isAfter(request.getExpiryDate())
                || request.getStartDate().isEqual(request.getExpiryDate())) {
            throw new IllegalArgumentException("Expiry date must be after start date.");
        }

        if (request.getDiscountType() == DiscountType.PERCENTAGE
                && request.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100.");
        }

        Coupon coupon = couponMapper.toEntity(request);

        coupon.setRestaurant(restaurant);
        // Stored normalised, which is what makes the indexed equality lookup possible.
        coupon.setCode(code);
        coupon.setUsedCount(0);

        coupon = couponRepository.save(coupon);

        return couponMapper.toResponse(coupon);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponResponse> getAllCoupons(Long restaurantId) {

        Restaurant restaurant = requireOwnRestaurant(restaurantId);

        return couponRepository.findByRestaurant(restaurant)
                .stream()
                .map(couponMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CouponResponse getCouponById(Long id) {

        Coupon coupon = getOwnCoupon(id);

        return couponMapper.toResponse(coupon);
    }

    @Override
    public CouponResponse updateCoupon(Long id, UpdateCouponRequest request) {

        Coupon coupon = getOwnCoupon(id);

        if (request.getStartDate().isAfter(request.getExpiryDate())
                || request.getStartDate().isEqual(request.getExpiryDate())) {
            throw new IllegalArgumentException("Expiry date must be after start date.");
        }

        if (request.getDiscountType() == DiscountType.PERCENTAGE
                && request.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100.");
        }

        couponMapper.updateEntity(request, coupon);

        coupon = couponRepository.save(coupon);

        return couponMapper.toResponse(coupon);
    }

    @Override
    public void deleteCoupon(Long id) {

        Coupon coupon = getOwnCoupon(id);

        couponRepository.delete(coupon);
    }

    /**
     * Quotes what a coupon would take off a given amount. Deliberately read-only: it does NOT
     * consume a use. A coupon is claimed once, when an order is placed (see OrderServiceImpl), so
     * checking a code - or checking it twice - costs nothing.
     */
    @Override
    @Transactional(readOnly = true)
    public ApplyCouponResponse applyCoupon(ApplyCouponRequest request) {

        Restaurant restaurant = requireOwnRestaurant(request.getRestaurantId());

        Coupon coupon = couponRepository
                .findByRestaurantAndNormalisedCode(restaurant, CouponCode.normalise(request.getCouponCode()))
                .orElseThrow(() ->
                        new IllegalArgumentException(CouponState.NOT_FOUND.message()));

        // This endpoint quotes against a bare amount with no basket, so the minimum-items rule
        // cannot be judged here; Integer.MAX_VALUE skips it rather than failing the quote. The
        // diner's cart, which knows its dishes, is where that rule is enforced.
        CouponPricing.Result result = CouponPricing.evaluate(
                CouponTerms.of(coupon), request.getOrderAmount(), Integer.MAX_VALUE, businessTime.now());

        if (!result.state().isValid()) {
            throw new IllegalArgumentException(result.state().message());
        }

        ApplyCouponResponse response = new ApplyCouponResponse();

        response.setCouponCode(coupon.getCode());
        response.setOrderAmount(request.getOrderAmount());
        response.setDiscountAmount(result.discount());
        response.setFinalAmount(result.payable());

        return response;
    }


    /**
     * The caller may only ever touch their own restaurant's coupons. The endpoints still accept a
     * restaurantId for compatibility, but it is checked against the signed-in user rather than
     * trusted: before this, any authenticated user could read or delete another restaurant's coupons.
     */
    private Restaurant requireOwnRestaurant(Long requestedId) {

        Restaurant current = currentUserService.getCurrentRestaurant();

        if (current == null) {
            throw new AccessDeniedException("This account is not attached to a restaurant.");
        }
        if (requestedId != null && !current.getId().equals(requestedId)) {
            throw new AccessDeniedException("That restaurant is not yours.");
        }
        return current;
    }

    /** Loads a coupon and refuses it if it belongs to another restaurant. */
    private Coupon getOwnCoupon(Long couponId) {

        Coupon coupon = getCoupon(couponId);
        Restaurant current = currentUserService.getCurrentRestaurant();

        if (current == null || !current.getId().equals(coupon.getRestaurant().getId())) {
            // Same answer as "no such coupon", so ids cannot be probed across restaurants.
            throw new IllegalArgumentException("Coupon not found.");
        }
        return coupon;
    }

    private Restaurant getRestaurant(Long restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Restaurant not found."));
    }

    private Coupon getCoupon(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Coupon not found."));
    }

    private void validateCoupon(Coupon coupon) {

        LocalDateTime now = LocalDateTime.now();

        if (!coupon.getActive()) {
            throw new IllegalArgumentException("Coupon is inactive.");
        }

        if (now.isBefore(coupon.getStartDate())) {
            throw new IllegalArgumentException("Coupon has not started yet.");
        }

        if (now.isAfter(coupon.getExpiryDate())) {
            throw new IllegalArgumentException("Coupon has expired.");
        }

        if (coupon.getUsageLimit() > 0 &&
                coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new IllegalArgumentException("Coupon usage limit exceeded.");
        }
    }

    private BigDecimal calculateDiscount(
            Coupon coupon,
            BigDecimal orderAmount) {

        BigDecimal discount;

        if (coupon.getDiscountType() == DiscountType.PERCENTAGE) {

            discount = orderAmount
                    .multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100));

            if (coupon.getMaximumDiscount() != null &&
                    discount.compareTo(coupon.getMaximumDiscount()) > 0) {

                discount = coupon.getMaximumDiscount();
            }

        } else {

            discount = coupon.getDiscountValue();
        }

        return discount.min(orderAmount);
    }
}