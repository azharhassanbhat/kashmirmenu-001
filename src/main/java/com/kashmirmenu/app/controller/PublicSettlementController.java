package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.concurrency.LockRetry;
import com.kashmirmenu.app.dto.request.ClaimPaidRequest;
import com.kashmirmenu.app.dto.request.CreateSettlementRequest;
import com.kashmirmenu.app.dto.response.SettlementResponse;
import com.kashmirmenu.app.metrics.FloorMetrics;
import com.kashmirmenu.app.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Paying at a table. Every call acts as the guest named by the X-Diner-Token header and answers 404
 * "session has ended" without a valid one, like the cart and bill endpoints. A guest reaches only the
 * settlements they started.
 *
 * <p>Starting one records the guest's choice and creates PENDING payments. Nothing is paid until staff
 * confirm it, so the response says PENDING until they do.
 */
@RestController
@RequestMapping("/api/public/tables/{qr}/settlements")
@RequiredArgsConstructor
public class PublicSettlementController {

    /** Sent by the client and reused when it retries; the same key from the same guest returns the same settlement. */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final String TOKEN_HEADER = PublicDinerController.TOKEN_HEADER;

    private final SettlementService settlementService;
    private final LockRetry retry;
    private final FloorMetrics metrics;

    @Operation(summary = "Start paying: my own bill (SEPARATE) or the whole table's (COMBINED)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse create(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateSettlementRequest request) {
        SettlementResponse created = retry.call(
                "settlement", () -> settlementService.create(qr, dinerToken, request, idempotencyKey));
        metrics.settlementStarted(created.kind().name());
        return created;
    }

    @Operation(summary = "The calling guest's own settlements, newest first")
    @GetMapping
    public List<SettlementResponse> list(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        return settlementService.list(qr, dinerToken);
    }

    @Operation(summary = "One of the calling guest's settlements, with its current status")
    @GetMapping("/{code}")
    public SettlementResponse get(
            @PathVariable String qr,
            @PathVariable String code,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        return settlementService.get(qr, dinerToken, code);
    }

    @Operation(summary = "UPI: say \"I've completed payment\", optionally with the transaction reference. "
            + "A claim only: staff must still confirm the money arrived")
    @PostMapping("/{code}/claim-paid")
    public SettlementResponse claimPaid(
            @PathVariable String qr,
            @PathVariable String code,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @Valid @RequestBody(required = false) ClaimPaidRequest request) {
        return retry.call("settlement", () -> settlementService.claimPaid(
                qr, dinerToken, code, request == null ? null : request.reference()));
    }

    @Operation(summary = "Withdraw a payment staff have not confirmed yet")
    @PostMapping("/{code}/cancel")
    public SettlementResponse cancel(
            @PathVariable String qr,
            @PathVariable String code,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        return retry.call("settlement", () -> settlementService.cancel(qr, dinerToken, code));
    }
}
