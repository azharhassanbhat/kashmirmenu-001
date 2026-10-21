package com.kashmirmenu.app.coupon;

import com.kashmirmenu.app.entity.Coupon;

/**
 * Maps a stored coupon onto the terms {@link CouponPricing} evaluates.
 *
 * <p>Kept out of {@code CouponPricing} itself so that class stays free of JPA entities and remains
 * unit-testable from plain values. It exists at all because the same mapping was written out twice -
 * once in {@code CartServiceImpl}, once in {@code CouponServiceImpl} - and a third copy was about to
 * be added in {@code OrderServiceImpl}. Three copies of a null-handling rule is three chances for
 * them to disagree.
 */
public final class CouponTerms {

    private CouponTerms() {
    }

    public static CouponPricing.Terms of(Coupon coupon) {
        return new CouponPricing.Terms(
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinimumOrderAmount(),
                coupon.getMaximumDiscount(),
                coupon.getMinimumItems() == null ? 0 : coupon.getMinimumItems(),
                coupon.getUsageLimit() == null ? 0 : coupon.getUsageLimit(),
                coupon.getUsedCount() == null ? 0 : coupon.getUsedCount(),
                coupon.getStartDate(),
                coupon.getExpiryDate(),
                Boolean.TRUE.equals(coupon.getActive()));
    }
}
