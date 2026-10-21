package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;

import java.time.LocalDateTime;

/**
 * What a guest sees of their own request. Deliberately no staff names or emails, and no other
 * guest's identity: only the type, the status and the times.
 *
 * @param alreadyRequested true when the guest asked for something that was already open (a repeat
 *                         tap); the existing request is returned rather than a second one made.
 * @param mine             whether this guest raised it (other guests' requests at the table are
 *                         visible so nobody calls the waiter twice, but only the requester may cancel).
 */
public record PublicServiceRequestResponse(
        String requestNumber,
        ServiceRequestType type,
        String typeLabel,
        ServiceRequestStatus status,
        String note,
        boolean mine,
        boolean alreadyRequested,
        LocalDateTime createdAt,
        LocalDateTime acknowledgedAt,
        LocalDateTime completedAt) {
}
