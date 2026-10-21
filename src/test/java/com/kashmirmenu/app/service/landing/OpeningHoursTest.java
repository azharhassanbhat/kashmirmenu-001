package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.landing.OpeningHours.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class OpeningHoursTest {

    // 2026-10-12 is a Monday
    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, 11 + day, hour, minute);
    }

    private static void refused(String text, String fragment) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> OpeningHours.parse(text));
        assertTrue(e.getMessage().toLowerCase().contains(fragment.toLowerCase()), e.getMessage());
    }

    @Test
    void blankHoursAreNotPublishedAndMakeNoClaim() {
        for (String blank : new String[]{null, "", "  \n  "}) {
            OpeningHours h = OpeningHours.parse(blank);
            assertFalse(h.isPublished());
            assertFalse(h.status(at(1, 12, 0)).known());
            assertTrue(h.week().isEmpty());
            assertEquals("", h.canonical());
        }
    }

    @Test
    void openAndClosedDuringTheDay() {
        OpeningHours h = OpeningHours.parse("Mon-Sun 09:00-22:00");
        Status open = h.status(at(1, 12, 0));
        assertTrue(open.known());
        assertTrue(open.open());
        assertEquals("Open now · closes 22:00", open.label());

        assertEquals("Closed · opens today 09:00", h.status(at(1, 7, 30)).label());
        assertEquals("Closed · opens tomorrow 09:00", h.status(at(1, 22, 0)).label());
    }

    @Test
    void theClosingMinuteIsClosedAndTheOpeningMinuteIsOpen() {
        OpeningHours h = OpeningHours.parse("Mon 09:00-22:00");
        assertTrue(h.status(at(1, 9, 0)).open());
        assertTrue(h.status(at(1, 21, 59)).open());
        assertFalse(h.status(at(1, 22, 0)).open());
        assertFalse(h.status(at(1, 8, 59)).open());
    }

    @Test
    void severalRangesInOneDay() {
        OpeningHours h = OpeningHours.parse("Mon 12:00-15:00, 19:00-23:00");
        assertTrue(h.status(at(1, 13, 0)).open());
        assertEquals("Closed · opens today 19:00", h.status(at(1, 16, 0)).label());
        assertTrue(h.status(at(1, 20, 0)).open());
    }

    @Test
    void aClosedDayAndTheNextOpening() {
        OpeningHours h = OpeningHours.parse("Mon-Fri 09:00-22:00\nSat,Sun closed");
        assertEquals("Closed · opens Mon 09:00", h.status(at(6, 12, 0)).label()); // Saturday noon
        assertEquals("Closed · opens tomorrow 09:00", h.status(at(7, 12, 0)).label()); // Sunday noon
    }

    @Test
    void aRangePastMidnightKeepsTheRestaurantOpenIntoTheNextDay() {
        OpeningHours h = OpeningHours.parse("Fri 18:00-02:00");
        assertTrue(h.status(at(5, 23, 0)).open());                 // Friday 23:00
        Status after = h.status(at(6, 1, 0));                       // Saturday 01:00
        assertTrue(after.open());
        assertEquals("Open now · closes 02:00", after.label());
        assertFalse(h.status(at(6, 2, 0)).open());                  // Saturday 02:00
    }

    @Test
    void aDayWithoutALineIsClosed() {
        OpeningHours h = OpeningHours.parse("Mon 09:00-17:00");
        assertFalse(h.status(at(3, 12, 0)).open());
        assertEquals("closed", h.week().get(2).hours());
        assertEquals(7, h.week().size());
    }

    @Test
    void aWeekendRangeWrapsAroundTheEndOfTheWeek() {
        OpeningHours h = OpeningHours.parse("Fri-Mon 10:00-23:00");
        assertTrue(h.status(at(7, 12, 0)).open()); // Sunday
        assertTrue(h.status(at(1, 12, 0)).open()); // Monday
        assertFalse(h.status(at(2, 12, 0)).open()); // Tuesday
    }

    @Test
    void theStoredTextIsTidiedIntoWeekOrder() {
        OpeningHours h = OpeningHours.parse("sunday closed\nmonday-wednesday 9-17");
        assertEquals("Mon 09:00-17:00\nTue 09:00-17:00\nWed 09:00-17:00\nSun closed", h.canonical());
        // and it parses back to the same thing
        assertEquals(h.canonical(), OpeningHours.parse(h.canonical()).canonical());
    }

    @Test
    void twentyFourHundredMeansUntilMidnightWithNoGapAtTheEnd() {
        OpeningHours h = OpeningHours.parse("Mon-Sun 00:00-24:00");
        assertTrue(h.status(at(1, 0, 0)).open());
        assertTrue(h.status(at(1, 12, 0)).open());
        assertTrue(h.status(java.time.LocalDateTime.of(2026, 10, 12, 23, 59, 59)).open(), "the very last second is open");
        assertEquals("Open now · closes 24:00", h.status(at(1, 12, 0)).label());
        assertEquals("Mon 00:00-24:00", h.canonical().split("\n")[0]);
        assertEquals(h.canonical(), OpeningHours.parse(h.canonical()).canonical(), "round-trips");
    }

    @Test
    void anEveningRangeEndingAtTwentyFourHundredStopsAtMidnightAndDoesNotSpillOver() {
        OpeningHours h = OpeningHours.parse("Fri 18:00-24:00");
        assertTrue(h.status(at(5, 23, 59)).open());
        assertFalse(h.status(at(6, 0, 0)).open(), "midnight itself is the end");
        assertFalse(h.status(at(6, 0, 30)).open());
    }

    @Test
    void onlyAWholeDayMayStartAndEndAtTheSameClockTime() {
        refused("Mon 00:00-00:00", "same");
        refused("Mon 09:00-09:00", "same");
        refused("Mon 09:00-24:00 ".replace("09:00", "24:01"), "24-hour time");
    }

    @Test
    void mistakesAreRefusedWithTheirLineNumber() {
        refused("Mon", "line 1");
        refused("Mon 09:00-22:00\nFunday 10:00-11:00", "line 2");
        refused("Mon 25:00-26:00", "24-hour time");
        refused("Mon 09:00", "09:00-22:00");
        refused("Mon 09:00-09:00", "same");
        refused("Mon 09:00-17:00\nMon 10:00-12:00", "already set");
        refused("Mon-Tue-Wed 09:00-17:00", "days");
    }

    @Test
    void absurdlyLongTextIsRefused() {
        refused("Mon 09:00-17:00\n".repeat(60), "too long");
    }
}
