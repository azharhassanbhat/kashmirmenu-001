package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.ServiceRequestStatus;
import com.kashmirmenu.app.enums.ServiceRequestType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What staff see. {@code waitingSeconds} is computed on the server from the business clock, so a
 * tablet with a wrong clock still shows the right wait.
 */
public record ServiceRequestResponse(
        Long id,
        String requestNumber,
        ServiceRequestType type,
        String typeLabel,
        ServiceRequestStatus status,
        boolean urgent,
        String note,
        Long tableId,
        String tableName,
        String requestedBy,
        Long assignedToUserId,
        String assignedToName,
        LocalDateTime createdAt,
        LocalDateTime acknowledgedAt,
        LocalDateTime completedAt,
        long waitingSeconds,
        List<Event> history) {

    public record Event(ServiceRequestStatus from, ServiceRequestStatus to, String actor, LocalDateTime at) {
    }
}
