package com.kashmirmenu.app.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * PENDING -> ACKNOWLEDGED -> IN_PROGRESS -> COMPLETED, or CANCELLED from any open state.
 *
 * <p>A request is never completed on submission, and PENDING cannot jump straight to COMPLETED: a
 * member of staff has to acknowledge it first, so "someone has seen this" is always on record.
 * COMPLETED is reachable from ACKNOWLEDGED because many requests (a glass of water) are done the
 * moment they are picked up, and forcing a pointless IN_PROGRESS tap would slow staff down.
 */
public enum ServiceRequestStatus {
    PENDING, ACKNOWLEDGED, IN_PROGRESS, COMPLETED, CANCELLED;

    public static final Set<ServiceRequestStatus> OPEN =
            EnumSet.of(PENDING, ACKNOWLEDGED, IN_PROGRESS);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    /** Re-applying the current status is a no-op, so a retried tap after a dropped response is harmless. */
    public boolean canMoveTo(ServiceRequestStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case PENDING -> next == ACKNOWLEDGED || next == CANCELLED;
            case ACKNOWLEDGED -> next == IN_PROGRESS || next == COMPLETED || next == CANCELLED;
            case IN_PROGRESS -> next == COMPLETED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
