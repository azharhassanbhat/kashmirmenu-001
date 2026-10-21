package com.kashmirmenu.app.coupon;

import com.kashmirmenu.app.enums.DiscountType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Coupon validity and discount arithmetic, kept pure so it can be unit tested and so the same rules
 * run everywhere a coupon is touched: the diner's preview, every cart recalculation, and the moment an
 * order is placed. Nothing here has a side effect - in particular, checking a coupon never consumes it.
 */
public final class CouponPricing {

    private CouponPricing() {
    }

    /**
     * The fields of a coupon this logic needs, so the maths does not depend on the JPA entity.
     *
     * @param minimumOrderAmount a currency amount the bill must reach; 0 for no requirement
     * @param minimumItems       a count of dishes the order must contain; 0 for no requirement.
     *                           Deliberately separate from the amount: the two are different units
     *                           and were being confused for one another.
     */
    public record Terms(
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal minimumOrderAmount,
            BigDecimal maximumDiscount,
            int minimumItems,
            int usageLimit,
            int usedCount,
            LocalDateTime startDate,
            LocalDateTime expiryDate,
            boolean active) {
    }

    /**
     * @param discount       what comes off, never more than the amount itself
     * @param shortfall      when BELOW_MINIMUM, how much more needs spending; otherwise zero
     * @param itemsShortfall when BELOW_MINIMUM_ITEMS, how many more dishes are needed; otherwise zero
     */
    public record Result(
            CouponState state,
            BigDecimal discount,
            BigDecimal payable,
            BigDecimal shortfall,
            int itemsShortfall) {

        public boolean applied() {
            return state.isValid() && discount.signum() > 0;
        }
    }

    /**
     * @param itemCount how many dishes the order contains (quantities summed), for the
     *                  minimum-items rule
     */
    public static Result evaluate(Terms terms, BigDecimal amount, int itemCount, LocalDateTime now) {
        BigDecimal base = amount == null ? BigDecimal.ZERO : amount;

        if (base.signum() <= 0) {
            return refused(CouponState.EMPTY_CART, base);
        }
        CouponState availability = availability(terms, now);
        if (availability != CouponState.VALID) {
            return refused(availability, base);
        }
        BigDecimal minimum = nz(terms.minimumOrderAmount());
        if (base.compareTo(minimum) < 0) {
            return new Result(CouponState.BELOW_MINIMUM, money(BigDecimal.ZERO), money(base),
                    money(minimum.subtract(base)), 0);
        }
        // Checked after the amount, so a diner who is short on both is told about the money first:
        // it is the harder of the two to satisfy by accident.
        if (terms.minimumItems() > 0 && itemCount < terms.minimumItems()) {
            return new Result(CouponState.BELOW_MINIMUM_ITEMS, money(BigDecimal.ZERO), money(base),
                    money(BigDecimal.ZERO), terms.minimumItems() - itemCount);
        }

        BigDecimal discount = discountFor(terms, base);
        return new Result(CouponState.VALID, discount, money(base.subtract(discount)),
                money(BigDecimal.ZERO), 0);
    }

    /** Validity that does not depend on the basket: active, in date, not used up. */
    public static CouponState availability(Terms terms, LocalDateTime now) {
        if (!terms.active()) {
            return CouponState.INACTIVE;
        }
        if (terms.startDate() != null && now.isBefore(terms.startDate())) {
            return CouponState.NOT_STARTED;
        }
        if (terms.expiryDate() != null && now.isAfter(terms.expiryDate())) {
            return CouponState.EXPIRED;
        }
        // A limit of zero means unlimited, which is the existing convention for usage_limit.
        if (terms.usageLimit() > 0 && terms.usedCount() >= terms.usageLimit()) {
            return CouponState.USED_UP;
        }
        return CouponState.VALID;
    }

    private static BigDecimal discountFor(Terms terms, BigDecimal base) {
        BigDecimal discount;

        if (terms.discountType() == DiscountType.PERCENTAGE) {
            // Scale and rounding are explicit: the original divide() had neither, so a percentage that
            // did not divide exactly would have thrown ArithmeticException.
            discount = base.multiply(nz(terms.discountValue()))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            discount = nz(terms.discountValue());
        }

        if (terms.maximumDiscount() != null && discount.compareTo(terms.maximumDiscount()) > 0) {
            discount = terms.maximumDiscount();
        }
        // A fixed-amount coupon worth more than the basket must not produce a negative bill.
        if (discount.compareTo(base) > 0) {
            discount = base;
        }
        if (discount.signum() < 0) {
            discount = BigDecimal.ZERO;
        }
        return money(discount);
    }

    private static Result refused(CouponState state, BigDecimal base) {
        return new Result(state, money(BigDecimal.ZERO), money(base), money(BigDecimal.ZERO), 0);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
