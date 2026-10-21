package com.kashmirmenu.app.service.landing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LandingActionsTest {

    @Test
    void nothingStoredMeansEverythingInTheDefaultOrder() {
        assertEquals(LandingActions.ALL, LandingActions.fromStored(null));
    }

    @Test
    void theDefaultOrderIsStoredAsNothingSoNewActionsAppearForRestaurantsThatNeverChoseAnOrder() {
        assertNull(LandingActions.toStored(LandingActions.ALL));
    }

    @Test
    void aCustomOrderRoundTripsAndKeysMissingFromItAreHidden() {
        String stored = LandingActions.toStored(List.of("menu", "review", "wifi"));
        assertEquals("MENU,REVIEW,WIFI", stored);
        assertEquals(List.of("MENU", "REVIEW", "WIFI"), LandingActions.fromStored(stored));
    }

    @Test
    void unknownKeysAndRepeatsAreDropped() {
        assertEquals(List.of("MENU", "WIFI"),
                LandingActions.normalise(Arrays.asList("MENU", "sudoku", null, "wifi", "MENU", "  ")));
    }

    @Test
    void hidingEverythingIsRememberedAsHidingEverything() {
        String stored = LandingActions.toStored(List.of());
        assertEquals("", stored);
        assertTrue(LandingActions.fromStored(stored).isEmpty());
    }
}
