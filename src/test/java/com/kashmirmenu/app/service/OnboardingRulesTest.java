package com.kashmirmenu.app.service;

import com.kashmirmenu.app.service.OnboardingRules.Counts;
import com.kashmirmenu.app.service.OnboardingRules.Progress;
import com.kashmirmenu.app.service.OnboardingRules.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Progress towards taking real orders, worked out only from what exists. */
class OnboardingRulesTest {

    private static final Counts NOTHING = new Counts(0, 0, 0, false, 0, 0);
    private static final Counts EVERYTHING = new Counts(3, 20, 8, true, 4, 2);

    private static Step step(Progress p, String key) {
        return p.steps().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void aBrandNewRestaurantHasDoneNothingAndIsToldWhatToDoFirst() {
        Progress p = OnboardingRules.evaluate(NOTHING);
        assertEquals(0, p.requiredDone());
        assertEquals(5, p.requiredTotal());
        assertEquals(0, p.percent());
        assertFalse(p.complete());
        assertEquals("categories", p.nextKey());
        assertTrue(p.steps().stream().noneMatch(Step::done));
    }

    @Test
    void everythingInPlaceIsComplete() {
        Progress p = OnboardingRules.evaluate(EVERYTHING);
        assertTrue(p.complete());
        assertEquals(100, p.percent());
        assertNull(p.nextKey());
        assertTrue(p.steps().stream().allMatch(Step::done));
    }

    @Test
    void theNextStepIsAlwaysTheFirstRequiredOneNotDone() {
        assertEquals("dishes", OnboardingRules.evaluate(new Counts(2, 0, 0, false, 0, 0)).nextKey());
        assertEquals("tables", OnboardingRules.evaluate(new Counts(2, 5, 0, false, 0, 0)).nextKey());
        assertEquals("payments", OnboardingRules.evaluate(new Counts(2, 5, 3, false, 0, 0)).nextKey());
        assertEquals("testOrder", OnboardingRules.evaluate(new Counts(2, 5, 3, true, 0, 0)).nextKey());
    }

    @Test
    void stepsAreDoneOutOfOrderToo() {
        Progress p = OnboardingRules.evaluate(new Counts(0, 0, 5, true, 0, 0));
        assertTrue(step(p, "tables").done());
        assertTrue(step(p, "payments").done());
        assertFalse(step(p, "categories").done());
        assertEquals("categories", p.nextKey());
        assertEquals(2, p.requiredDone());
    }

    @Test
    void percentIsTheShareOfRequiredStepsDone() {
        assertEquals(20, OnboardingRules.evaluate(new Counts(1, 0, 0, false, 0, 0)).percent());
        assertEquals(40, OnboardingRules.evaluate(new Counts(1, 1, 0, false, 0, 0)).percent());
        assertEquals(60, OnboardingRules.evaluate(new Counts(1, 1, 1, false, 0, 0)).percent());
        assertEquals(80, OnboardingRules.evaluate(new Counts(1, 1, 1, true, 0, 0)).percent());
    }

    @Test
    void theTeamStepIsOptionalAndNeverBlocksGoingLive() {
        Progress p = OnboardingRules.evaluate(new Counts(1, 1, 1, true, 1, 0));
        assertTrue(p.complete());
        assertFalse(step(p, "team").done());
        assertFalse(step(p, "team").required());
        assertEquals(100, p.percent());
    }

    @Test
    void aCategoryWithNoDishesDoesNotCountAsHavingDishes() {
        Progress p = OnboardingRules.evaluate(new Counts(4, 0, 0, false, 0, 0));
        assertTrue(step(p, "categories").done());
        assertFalse(step(p, "dishes").done());
    }

    @Test
    void everyStepSaysWhereToDoIt() {
        for (Step s : OnboardingRules.evaluate(NOTHING).steps()) {
            assertTrue(s.route().startsWith("/admin"), s.key());
            assertFalse(s.title().isBlank());
            assertFalse(s.hint().isBlank());
        }
    }

    @Test
    void stepKeysAreUnique() {
        var keys = OnboardingRules.evaluate(NOTHING).steps().stream().map(Step::key).distinct().count();
        assertEquals(OnboardingRules.evaluate(NOTHING).steps().size(), keys);
    }

    @Test
    void theStepListCannotBeChangedByTheCaller() {
        Progress p = OnboardingRules.evaluate(NOTHING);
        assertThrows(UnsupportedOperationException.class, () -> p.steps().clear());
    }
}
