package com.kashmirmenu.app.coupon;

import com.kashmirmenu.app.enums.DiscountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouponPricingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static CouponPricing.Terms percentage(String value, String minimum, String cap) {
        return new CouponPricing.Terms(DiscountType.PERCENTAGE, d(value), d(minimum),
                cap == null ? null : d(cap), 0, 0, 0, NOW.minusDays(1), NOW.plusDays(1), true);
    }

    private static CouponPricing.Terms fixed(String value, String minimum) {
        return new CouponPricing.Terms(DiscountType.FIXED, d(value), d(minimum), null,
                0, 0, 0, NOW.minusDays(1), NOW.plusDays(1), true);
    }

    /** A coupon requiring a number of dishes as well as (or instead of) an amount. */
    private static CouponPricing.Terms needingItems(int items, String minimumAmount) {
        return new CouponPricing.Terms(DiscountType.FIXED, d("100"), d(minimumAmount), null,
                items, 0, 0, NOW.minusDays(1), NOW.plusDays(1), true);
    }

    /** Most rules do not involve the dish count, so these pass a basket large enough to clear it. */
    private static CouponPricing.Result evaluate(CouponPricing.Terms terms, BigDecimal amount) {
        return CouponPricing.evaluate(terms, amount, 99, NOW);
    }

    @Test
    void takesAPercentageOffTheBasket() {
        CouponPricing.Result r = evaluate(percentage("10", "0", null), d("1200.00"));
        assertEquals(CouponState.VALID, r.state());
        assertEquals(d("120.00"), r.discount());
        assertEquals(d("1080.00"), r.payable());
    }

    @Test
    void roundsAPercentageThatDoesNotDivideExactly() {
        // The original code called divide() with no scale, which throws on a non-terminating result.
        CouponPricing.Result r = evaluate(percentage("33", "0", null), d("99.99"));
        assertEquals(d("33.00"), r.discount());
        assertEquals(d("66.99"), r.payable());
    }

    @Test
    void respectsTheMaximumDiscount() {
        CouponPricing.Result r = evaluate(percentage("50", "0", "150"), d("1200.00"));
        assertEquals(d("150.00"), r.discount());
    }

    @Test
    void aFixedCouponWorthMoreThanTheBasketNeverMakesTheBillNegative() {
        CouponPricing.Result r = evaluate(fixed("500", "0"), d("300.00"));
        assertEquals(d("300.00"), r.discount());
        assertEquals(d("0.00"), r.payable());
    }

    @Test
    void asksForMoreSpendBelowTheMinimum_andSaysHowMuch() {
        CouponPricing.Result r = evaluate(percentage("20", "1000", null), d("750.00"));
        assertEquals(CouponState.BELOW_MINIMUM, r.state());
        assertEquals(d("0.00"), r.discount());
        assertEquals(d("250.00"), r.shortfall());
        assertEquals(d("750.00"), r.payable());
        assertFalse(r.applied());
    }

    @Test
    void anEmptyBasketCannotCarryACoupon() {
        assertEquals(CouponState.EMPTY_CART,
                evaluate(percentage("20", "0", null), BigDecimal.ZERO).state());
    }

    @Test
    void refusesCouponsThatAreNotCurrentlyUsable() {
        CouponPricing.Terms inactive = new CouponPricing.Terms(DiscountType.FIXED, d("50"), d("0"), null,
                0, 0, 0, NOW.minusDays(1), NOW.plusDays(1), false);
        assertEquals(CouponState.INACTIVE, evaluate(inactive, d("500")).state());

        CouponPricing.Terms early = new CouponPricing.Terms(DiscountType.FIXED, d("50"), d("0"), null,
                0, 0, 0, NOW.plusDays(1), NOW.plusDays(2), true);
        assertEquals(CouponState.NOT_STARTED, evaluate(early, d("500")).state());

        CouponPricing.Terms expired = new CouponPricing.Terms(DiscountType.FIXED, d("50"), d("0"), null,
                0, 0, 0, NOW.minusDays(5), NOW.minusDays(1), true);
        assertEquals(CouponState.EXPIRED, evaluate(expired, d("500")).state());
    }

    @Test
    void stopsAtTheUsageLimit_andTreatsZeroAsUnlimited() {
        CouponPricing.Terms usedUp = new CouponPricing.Terms(DiscountType.FIXED, d("50"), d("0"), null,
                0, 10, 10, NOW.minusDays(1), NOW.plusDays(1), true);
        assertEquals(CouponState.USED_UP, evaluate(usedUp, d("500")).state());

        CouponPricing.Terms unlimited = new CouponPricing.Terms(DiscountType.FIXED, d("50"), d("0"), null,
                0, 0, 9999, NOW.minusDays(1), NOW.plusDays(1), true);
        assertTrue(evaluate(unlimited, d("500")).applied());
    }

    @Test
    void everyRefusalHasSomethingToShowTheDiner() {
        for (CouponState state : CouponState.values()) {
            if (state != CouponState.VALID) {
                assertTrue(state.message() != null && !state.message().isBlank(), state.name());
            }
        }
    }

    @Test
    void discountAndPayableAlwaysAddUpToTheBasket() {
        for (String amount : new String[]{"1.00", "99.99", "250.50", "1200.00", "18545.75"}) {
            for (CouponPricing.Terms terms : new CouponPricing.Terms[]{
                    percentage("10", "0", null), percentage("33", "0", "100"),
                    fixed("150", "0"), fixed("50000", "0")}) {
                CouponPricing.Result r = evaluate(terms, d(amount));
                assertEquals(d(amount).setScale(2), r.discount().add(r.payable()), amount);
            }
        }
    }

    @Test
    void aCouponCanRequireANumberOfDishes_separatelyFromTheAmount() {
        // "Spend ₹200 and order at least 2 dishes." A ₹350 single dish clears the money but not
        // the count: the two requirements are different units and are checked separately.
        CouponPricing.Terms terms = needingItems(2, "200");

        CouponPricing.Result oneDish = CouponPricing.evaluate(terms, d("350.00"), 1, NOW);
        assertEquals(CouponState.BELOW_MINIMUM_ITEMS, oneDish.state());
        assertEquals(1, oneDish.itemsShortfall());
        assertEquals(d("0.00"), oneDish.discount());

        CouponPricing.Result twoDishes = CouponPricing.evaluate(terms, d("350.00"), 2, NOW);
        assertEquals(CouponState.VALID, twoDishes.state());
        assertEquals(d("100.00"), twoDishes.discount());
    }

    @Test
    void theAmountIsReportedFirstWhenBothAreShort() {
        // Being short of money is the harder problem, so it is the one the diner hears about.
        CouponPricing.Result r = CouponPricing.evaluate(needingItems(3, "500"), d("100.00"), 1, NOW);
        assertEquals(CouponState.BELOW_MINIMUM, r.state());
        assertEquals(d("400.00"), r.shortfall());
        assertEquals(0, r.itemsShortfall());
    }

    @Test
    void zeroMinimumItemsMeansNoRequirement() {
        CouponPricing.Result r = CouponPricing.evaluate(needingItems(0, "0"), d("50.00"), 1, NOW);
        assertTrue(r.applied());
    }

    @Test
    void aDishCountCanBeRequiredWithoutAnyMinimumSpend() {
        CouponPricing.Terms terms = needingItems(2, "0");
        assertEquals(CouponState.BELOW_MINIMUM_ITEMS,
                CouponPricing.evaluate(terms, d("1000.00"), 1, NOW).state());
        assertTrue(CouponPricing.evaluate(terms, d("150.00"), 2, NOW).applied());
    }

    @Test
    void howManyMoreDishesAreNeededIsReported() {
        assertEquals(3, CouponPricing.evaluate(needingItems(4, "0"), d("500.00"), 1, NOW).itemsShortfall());
    }
}
