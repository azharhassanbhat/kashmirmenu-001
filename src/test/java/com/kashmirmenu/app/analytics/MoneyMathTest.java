package com.kashmirmenu.app.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MoneyMathTest {

    @Test
    void formatsRupeesWithIndianGrouping() {
        assertEquals("₹1,85,450", MoneyMath.format(new BigDecimal("185450"), "INR"));
        assertEquals("₹999", MoneyMath.format(new BigDecimal("999.00"), "INR"));
        assertEquals("₹1,234.50", MoneyMath.format(new BigDecimal("1234.5"), "INR"));
        assertEquals("₹1,23,45,678.90", MoneyMath.format(new BigDecimal("12345678.9"), "INR"));
        assertEquals("₹0", MoneyMath.format(null, "INR"));
        assertEquals("-₹1,500", MoneyMath.format(new BigDecimal("-1500"), "INR"));
    }

    @Test
    void otherCurrenciesGetACodeAndWesternGrouping() {
        assertEquals("USD 1,234,567", MoneyMath.format(new BigDecimal("1234567"), "usd"));
    }

    @Test
    void percentagesUseExactDecimals() {
        // 0.1 + 0.2 style float errors must not appear: 1 / 3 of 100.00.
        assertEquals(new BigDecimal("33.3"), MoneyMath.percentOf(new BigDecimal("1.00"), new BigDecimal("3.00")));
        assertEquals(new BigDecimal("0.0"), MoneyMath.percentOf(new BigDecimal("5"), BigDecimal.ZERO));
    }

    @Test
    void percentChange_hasNoBaseWhenPreviousIsZero() {
        assertNull(MoneyMath.percentChange(new BigDecimal("100"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("18.0"), MoneyMath.percentChange(new BigDecimal("118"), new BigDecimal("100")));
        assertEquals(new BigDecimal("-25.0"), MoneyMath.percentChange(new BigDecimal("75"), new BigDecimal("100")));
    }

    @Test
    void average_roundsHalfUpToPaise() {
        assertEquals(new BigDecimal("333.33"), MoneyMath.average(new BigDecimal("1000.00"), 3));
        assertEquals(new BigDecimal("0.00"), MoneyMath.average(new BigDecimal("1000.00"), 0));
        assertEquals(new BigDecimal("0.67"), MoneyMath.average(new BigDecimal("2.00"), 3));
    }
}
