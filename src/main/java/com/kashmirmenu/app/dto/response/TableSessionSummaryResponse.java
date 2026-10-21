package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.TablePayState;
import com.kashmirmenu.app.enums.TableSessionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One table visit as a line on the owner's live view.
 *
 * <p>due: payable now. inProgress: a payment started and waiting for staff. paid: received.
 * awaitingConfirmation: owed, but the kitchen has not confirmed the order yet. Together they account for
 * everything billed, and {@code paid} is counted once per payment record, so nothing is double counted.
 *
 * <p>{@code lastActivityAt} is when any guest last acted. {@code idle} means nobody has for longer than the
 * configured time; {@code needsStaff} means idle while money is still owed, which is never closed
 * automatically and is waiting for a person to decide.
 */
public record TableSessionSummaryResponse(
        Long id,
        String visitCode,
        String tableName,
        TableSessionStatus status,
        LocalDateTime openedAt,
        LocalDateTime lastActivityAt,
        int guestCount,
        BigDecimal due,
        BigDecimal inProgress,
        BigDecimal paid,
        BigDecimal awaitingConfirmation,
        TablePayState payState,
        boolean idle,
        boolean needsStaff,
        LocalDateTime closedAt,
        String closeReason) {
}
