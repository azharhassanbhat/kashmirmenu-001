package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * "My bill": the calling guest's own orders and what they still owe. Nobody else's orders appear.
 *
 * <p>due: payable now. inProgress: a payment started, awaiting staff confirmation. paid: received.
 * awaitingConfirmation: owed, but on orders the kitchen has not confirmed yet, so not payable yet.
 */
public record DinerBillResponse(
        String displayName,
        String visitId,
        List<BillOrderResponse> orders,
        BigDecimal due,
        BigDecimal inProgress,
        BigDecimal paid,
        BigDecimal awaitingConfirmation) {
}
