package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OptionKind;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.OptionPricing.Group;
import com.kashmirmenu.app.service.OptionPricing.Opt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What a guest is charged for a dish with options, and which choices are refused. */
class OptionPricingTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Opt opt(long id, String name, String delta, boolean available) {
        return new Opt(id, name, d(delta), available);
    }

    /** Biryani: required size (half is cheaper), optional add-ons (max 2), optional spice level. */
    private final List<Group> biryani = List.of(
            new Group(1L, "Portion", OptionKind.SINGLE, true, null, List.of(
                    opt(10, "Half", "-80.00", true), opt(11, "Full", "0.00", true), opt(12, "Family", "250.00", true))),
            new Group(2L, "Extras", OptionKind.MULTI, false, 2, List.of(
                    opt(20, "Raita", "30.00", true), opt(21, "Extra egg", "20.00", true),
                    opt(22, "Salan", "25.00", true), opt(23, "Papad", "10.00", false))),
            new Group(3L, "Spice", OptionKind.SINGLE, false, null, List.of(
                    opt(30, "Mild", "0.00", true), opt(31, "Hot", "0.00", true))));

    private static final BigDecimal BASE = d("320.00");

    @Test
    void priceIsBasePlusEveryChosenChange() {
        var p = OptionPricing.price(BASE, biryani, List.of(12L, 20L, 21L, 31L));
        assertEquals(d("620.00"), p.unitPrice()); // 320 + 250 + 30 + 20
        assertEquals(4, p.chosen().size());
    }

    @Test
    void aNegativeChangeMakesAHalfPortionCheaper() {
        assertEquals(d("240.00"), OptionPricing.price(BASE, biryani, List.of(10L)).unitPrice());
    }

    @Test
    void theFullPortionCostsTheBasePrice() {
        assertEquals(d("320.00"), OptionPricing.price(BASE, biryani, List.of(11L)).unitPrice());
    }

    @Test
    void aRequiredGroupMustBeChosen() {
        var e = assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, List.of(20L)));
        assertTrue(e.getMessage().contains("Portion"));
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, null));
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, List.of()));
    }

    @Test
    void aDishWithNoGroupsNeedsNoChoicesAndKeepsItsPrice() {
        var p = OptionPricing.price(BASE, List.of(), null);
        assertEquals(d("320.00"), p.unitPrice());
        assertEquals("", p.key());
        assertTrue(p.chosen().isEmpty());
    }

    @Test
    void aSingleGroupAllowsOnlyOne() {
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, List.of(10L, 11L)));
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, List.of(11L, 30L, 31L)));
    }

    @Test
    void aMultiGroupHonoursItsMaximum() {
        assertEquals(d("375.00"), OptionPricing.price(BASE, biryani, List.of(11L, 20L, 22L)).unitPrice());
        var e = assertThrows(BadRequestException.class,
                () -> OptionPricing.price(BASE, biryani, List.of(11L, 20L, 21L, 22L)));
        assertTrue(e.getMessage().contains("2"));
    }

    @Test
    void anUnavailableOptionIsRefusedByName() {
        var e = assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, List.of(11L, 23L)));
        assertTrue(e.getMessage().contains("Papad"));
    }

    @Test
    void anOptionFromAnotherDishIsRefusedAndNeverPriced() {
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, List.of(11L, 999L)));
    }

    @Test
    void aNullIdIsRefused() {
        var ids = new java.util.ArrayList<Long>();
        ids.add(11L);
        ids.add(null);
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, ids));
    }

    @Test
    void repeatedIdsCountOnce() {
        var p = OptionPricing.price(BASE, biryani, List.of(11L, 20L, 20L, 20L));
        assertEquals(d("350.00"), p.unitPrice());
        assertEquals(2, p.chosen().size());
    }

    @Test
    void theSameChoicesInAnyOrderGiveTheSameKeySoTheyMergeIntoOneLine() {
        var a = OptionPricing.price(BASE, biryani, List.of(31L, 20L, 11L));
        var b = OptionPricing.price(BASE, biryani, List.of(11L, 31L, 20L));
        assertEquals(a.key(), b.key());
        assertEquals("11,20,31", a.key());
    }

    @Test
    void differentChoicesGiveDifferentKeysSoTheyStaySeparateLines() {
        assertNotEquals(
                OptionPricing.price(BASE, biryani, List.of(11L)).key(),
                OptionPricing.price(BASE, biryani, List.of(10L)).key());
    }

    @Test
    void aComboThatWouldCostNothingOrLessIsRefused() {
        var groups = List.of(new Group(1L, "Size", OptionKind.SINGLE, true, null,
                List.of(opt(1, "Free?", "-100.00", true))));
        assertThrows(BadRequestException.class, () -> OptionPricing.price(d("100.00"), groups, List.of(1L)));
        assertThrows(BadRequestException.class, () -> OptionPricing.price(d("50.00"), groups, List.of(1L)));
        assertEquals(d("1.00"), OptionPricing.price(d("101.00"), groups, List.of(1L)).unitPrice());
    }

    @Test
    void aFloodOfIdsIsRefused() {
        var ids = new java.util.ArrayList<Long>();
        for (long i = 1; i <= OptionPricing.MAX_SELECTED + 1; i++) {
            ids.add(i);
        }
        assertThrows(BadRequestException.class, () -> OptionPricing.price(BASE, biryani, ids));
    }

    @Test
    void theResultIsAlwaysTwoDecimalPlaces() {
        var groups = List.of(new Group(1L, "X", OptionKind.MULTI, false, null, List.of(opt(1, "a", "0.005", true))));
        assertEquals(2, OptionPricing.price(d("10"), groups, List.of(1L)).unitPrice().scale());
    }

    @Test
    void namesAndPricesComeFromTheGroupsNotTheCaller() {
        var p = OptionPricing.price(BASE, biryani, List.of(11L, 20L));
        var raita = p.chosen().stream().filter(c -> c.optionId() == 20L).findFirst().orElseThrow();
        assertEquals("Extras", raita.groupName());
        assertEquals("Raita", raita.optionName());
        assertEquals(d("30.00"), raita.priceDelta());
    }

    @Test
    void lowestPossiblePriceIsTheBaseMinusTheBiggestDiscountsAGuestCouldStack() {
        // Required portion: Half (-80). Extras add money only. Spice free.
        assertEquals(d("240.00"), OptionPricing.lowestPossiblePrice(BASE, biryani));
        var stacked = List.of(
                new Group(1L, "Size", OptionKind.SINGLE, true, null, List.of(opt(1, "Small", "-50.00", true), opt(2, "Big", "0.00", true))),
                new Group(2L, "Deals", OptionKind.MULTI, false, 2, List.of(
                        opt(3, "Skip rice", "-20.00", true), opt(4, "Skip salad", "-10.00", true), opt(5, "Skip drink", "-5.00", true))));
        // -50, then the two biggest of the optional discounts (-20, -10): 200 - 80
        assertEquals(d("120.00"), OptionPricing.lowestPossiblePrice(d("200.00"), stacked));
    }

    @Test
    void anOptionalSingleGroupNeverForcesAPositiveChangeIntoTheLowestPrice() {
        var g = List.of(new Group(1L, "Side", OptionKind.SINGLE, false, null, List.of(opt(1, "Fries", "40.00", true))));
        assertEquals(d("100.00"), OptionPricing.lowestPossiblePrice(d("100.00"), g));
    }

    @Test
    void aRequiredGroupOfOnlyPaidOptionsForcesTheCheapestOne() {
        var g = List.of(new Group(1L, "Size", OptionKind.SINGLE, true, null,
                List.of(opt(1, "M", "10.00", true), opt(2, "L", "30.00", true))));
        assertEquals(d("110.00"), OptionPricing.lowestPossiblePrice(d("100.00"), g));
    }
}
