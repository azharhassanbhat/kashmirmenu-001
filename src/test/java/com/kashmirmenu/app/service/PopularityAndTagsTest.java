package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.Allergen;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PopularityAndTagsTest {

    @Test
    void thePopularBadgeGoesToTheTopThreeSellersAndTiesGoToTheLowerId() {
        Map<Long, Long> sold = new HashMap<>();
        sold.put(1L, 50L);
        sold.put(2L, 40L);
        sold.put(3L, 40L);
        sold.put(4L, 30L);
        sold.put(5L, 4L);
        assertEquals(Set.of(1L, 2L, 3L), PopularityRules.pick(sold));
    }

    @Test
    void aDishNeedsAMinimumNumberOfSalesBeforeItIsCalledPopular() {
        assertTrue(PopularityRules.pick(Map.of(1L, 4L, 2L, 1L)).isEmpty());
        assertEquals(Set.of(9L), PopularityRules.pick(Map.of(9L, 5L)));
        assertTrue(PopularityRules.pick(Map.of()).isEmpty());
    }

    @Test
    void undeclaredAndDeclaredNoneAreDifferentInStorage() {
        assertNull(MenuTags.join(null, Allergen.class));
        assertEquals("", MenuTags.join(List.of(), Allergen.class));
        assertTrue(MenuTags.parse(null, Allergen.class).isEmpty());
        assertTrue(MenuTags.parse("", Allergen.class).isEmpty());
    }

    @Test
    void codesAreStoredSortedAndWithoutDuplicates() {
        assertEquals("MILK,SOY", MenuTags.join(List.of(Allergen.SOY, Allergen.MILK, Allergen.SOY), Allergen.class));
        assertEquals(List.of(Allergen.MILK, Allergen.EGG, Allergen.SOY), MenuTags.parse("SOY,MILK, EGG", Allergen.class));
    }

    @Test
    void aCodeRemovedFromTheVocabularyIsSkippedNotFatal() {
        assertEquals(List.of(Allergen.MILK), MenuTags.parse("MILK,ALIEN", Allergen.class));
    }

    @Test
    void labelsAreForPeopleAndCodesAreForMachines() {
        var tag = MenuTags.tags(List.of(Allergen.TREE_NUT), Allergen::label).get(0);
        assertEquals("TREE_NUT", tag.code());
        assertEquals("Tree nuts", tag.label());
    }
}
