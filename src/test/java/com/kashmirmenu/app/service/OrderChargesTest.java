package com.kashmirmenu.app.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Service charge and tax arithmetic: no rate is built in, discounts come off first, rounding is half up. */
class OrderChargesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static OrderCharges.Breakdown calc(String subtotal, String discount, String service, String tax,
                                               boolean inclusive) {
        return OrderCharges.compute(d(subtotal), d(discount), service == null ? null : d(service),
                tax == null ? null : d(tax), inclusive);
    }

    @Test
    void withNoRatesTheTotalIsExactlySubtotalMinusDiscountAsItAlwaysWas() {
        var b = calc("1000.00", "100.00", null, null, false);
        assertEquals(d("900.00"), b.total());
        assertEquals(d("0.00"), b.tax());
        assertEquals(d("0.00"), b.serviceCharge());
    }

    @Test
    void exclusiveTaxIsAddedOnTopAndTheServiceChargeIsTaxedToo() {
        var b = calc("1000.00", "0.00", "10", "5", false);
        assertEquals(d("100.00"), b.serviceCharge());
        assertEquals(d("55.00"), b.tax());          // 5% of 1000 + 100
        assertEquals(d("1155.00"), b.total());
        assertEquals(d("0.00"), b.taxIncluded());
    }

    @Test
    void aCouponReducesWhatIsChargedAndTaxedNotTheOtherWayRound() {
        var b = calc("1000.00", "200.00", "10", "5", false);
        assertEquals(d("800.00"), b.net());
        assertEquals(d("80.00"), b.serviceCharge());
        assertEquals(d("44.00"), b.tax());
        assertEquals(d("924.00"), b.total());
    }

    @Test
    void inclusivePricingAddsNothingAndShowsTheTaxInsideTheDishes() {
        var b = calc("1050.00", "0", "0", "5", true);
        assertEquals(d("1050.00"), b.total());
        assertEquals(d("0.00"), b.tax());
        assertEquals(d("50.00"), b.taxIncluded());   // 1050 x 5 / 105
    }

    @Test
    void aServiceChargeOnInclusivePricesIsAddedWithoutFurtherTax() {
        var b = calc("1050.00", "0", "10", "5", true);
        assertEquals(d("105.00"), b.serviceCharge());
        assertEquals(d("1155.00"), b.total());
    }

    @Test
    void roundingIsHalfUpToTwoPlaces() {
        var b = calc("333.33", "0", "0", "5", false);   // 16.6665
        assertEquals(d("16.67"), b.tax());
        assertEquals(d("350.00"), b.total());
        assertEquals(2, calc("10", "0", "1", "1", false).total().scale());
    }

    @Test
    void aDiscountLargerThanTheSubtotalNeverMakesAnythingNegative() {
        var b = calc("100.00", "150.00", "10", "5", false);
        assertEquals(d("0.00"), b.net());
        assertEquals(d("0.00"), b.total());
    }

    @Test
    void anEmptyCartCostsNothing() {
        assertEquals(d("0.00"), calc("0", "0", "10", "5", false).total());
    }

    @Test
    void theTotalIsAlwaysNetPlusServiceChargePlusTax() {
        var b = calc("777.77", "33.33", "7.5", "12", false);
        assertEquals(b.net().add(b.serviceCharge()).add(b.tax()), b.total());
    }

    @Test
    void ratesBelowZeroOrAboveOneHundredAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> calc("1", "0", "-1", "0", false));
        assertThrows(IllegalArgumentException.class, () -> calc("1", "0", "0", "100.01", false));
        assertEquals(d("2.00"), calc("1", "0", "0", "100", false).total());
    }
}
