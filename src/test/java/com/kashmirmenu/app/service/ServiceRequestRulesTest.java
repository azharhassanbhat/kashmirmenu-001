package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;
import com.kashmirmenu.app.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static com.kashmirmenu.app.enums.ServiceRequestStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** The pure rules of service requests: lifecycle, which types may repeat, note clean-up. */
class ServiceRequestRulesTest {

    @Test
    void theLifecycleRunsPendingToAcknowledgedToInProgressToCompleted() {
        assertTrue(PENDING.canMoveTo(ACKNOWLEDGED));
        assertTrue(ACKNOWLEDGED.canMoveTo(IN_PROGRESS));
        assertTrue(IN_PROGRESS.canMoveTo(COMPLETED));
    }

    @Test
    void aRequestCanNeverBeCompletedWithoutBeingAcknowledgedFirst() {
        assertFalse(PENDING.canMoveTo(COMPLETED));
        assertFalse(PENDING.canMoveTo(IN_PROGRESS));
    }

    @Test
    void aSimpleRequestMayBeCompletedStraightAfterAcknowledging() {
        assertTrue(ACKNOWLEDGED.canMoveTo(COMPLETED));
    }

    @Test
    void anyOpenRequestCanBeCancelled() {
        for (ServiceRequestStatus open : ServiceRequestStatus.OPEN) {
            assertTrue(open.canMoveTo(CANCELLED), open + " should be cancellable");
        }
    }

    @Test
    void finishedRequestsAreTerminalAndNeverGoBackwards() {
        for (ServiceRequestStatus finished : new ServiceRequestStatus[]{COMPLETED, CANCELLED}) {
            for (ServiceRequestStatus next : ServiceRequestStatus.values()) {
                if (next != finished) {
                    assertFalse(finished.canMoveTo(next), finished + " -> " + next + " must be refused");
                }
            }
        }
        assertFalse(IN_PROGRESS.canMoveTo(PENDING));
        assertFalse(ACKNOWLEDGED.canMoveTo(PENDING));
    }

    @Test
    void reapplyingTheCurrentStatusIsAHarmlessRetryNotAnError() {
        for (ServiceRequestStatus s : ServiceRequestStatus.values()) {
            assertTrue(s.canMoveTo(s));
        }
    }

    @Test
    void openMeansPendingAcknowledgedOrInProgress() {
        assertTrue(PENDING.isOpen());
        assertTrue(ACKNOWLEDGED.isOpen());
        assertTrue(IN_PROGRESS.isOpen());
        assertFalse(COMPLETED.isOpen());
        assertFalse(CANCELLED.isOpen());
    }

    @Test
    void onlyOtherMayBeRaisedSeveralTimesAtOnce() {
        for (ServiceRequestType type : ServiceRequestType.values()) {
            assertEquals(type == ServiceRequestType.OTHER, type.allowsSeveralOpen(), type.name());
        }
    }

    @Test
    void everyTypeHasALabelStaffCanRead() {
        for (ServiceRequestType type : ServiceRequestType.values()) {
            assertFalse(type.label().isBlank(), type.name());
        }
    }

    @Test
    void notesAreTrimmedAndWhitespaceIsCollapsed() {
        assertEquals("less spicy please", ServiceRequestNotes.clean("  less   spicy \n please  "));
    }

    @Test
    void controlCharactersAreStrippedFromNotes() {
        assertEquals("table is sticky", ServiceRequestNotes.clean("table\u0000 is\u0007 sticky"));
    }

    @Test
    void blankNotesBecomeNull() {
        assertNull(ServiceRequestNotes.clean(null));
        assertNull(ServiceRequestNotes.clean("   \n\t "));
    }

    @Test
    void notesLongerThan200CharactersAreRefused() {
        assertThrows(BadRequestException.class, () -> ServiceRequestNotes.clean("x".repeat(201)));
        assertEquals(200, ServiceRequestNotes.clean("x".repeat(200)).length());
    }

    @Test
    void notesInOtherScriptsSurvive() {
        assertEquals("پانی چاہیے", ServiceRequestNotes.clean("پانی چاہیے"));
    }
}
