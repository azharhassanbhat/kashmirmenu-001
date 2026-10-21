package com.kashmirmenu.app.dto.response;

import java.util.List;

/** Everything about one table visit, for staff: its guests and their orders, and its payment history. */
public record TableSessionDetailResponse(
        TableSessionSummaryResponse summary,
        /** Who closed it, or null if it is open or closed automatically. */
        String closedBy,
        List<VisitGuestResponse> guests,
        /** Settlements started during the visit, newest first, with what each covers. */
        List<SettlementStaffResponse> settlements,
        /** Every payment record of the visit, oldest first. */
        List<VisitPaymentResponse> payments) {
}
