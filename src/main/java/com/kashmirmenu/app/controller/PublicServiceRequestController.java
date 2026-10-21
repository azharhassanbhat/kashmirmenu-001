package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.CreateServiceRequestRequest;
import com.kashmirmenu.app.dto.response.PublicServiceRequestResponse;
import com.kashmirmenu.app.service.ServiceRequestService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Table-side requests raised by a guest. Unauthenticated like the rest of /api/public: the table's
 * QR code plus the guest's X-Diner-Token (from POST /tables/{qr}/join) identify who is asking and
 * where. Without a valid token these answer 404 "session has ended", exactly like the cart.
 */
@RestController
@RequestMapping("/api/public/tables/{qr}/service-requests")
@RequiredArgsConstructor
public class PublicServiceRequestController {

    private static final String TOKEN_HEADER = PublicDinerController.TOKEN_HEADER;

    private final ServiceRequestService serviceRequestService;

    @Operation(summary = "Ask for something at the table (waiter, water, bill, ...). A repeat of an open request returns it with 200.")
    @PostMapping
    public ResponseEntity<PublicServiceRequestResponse> create(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @Valid @RequestBody CreateServiceRequestRequest request) {
        PublicServiceRequestResponse created = serviceRequestService.submit(qr, dinerToken, request);
        return ResponseEntity
                .status(created.alreadyRequested() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(created);
    }

    @Operation(summary = "Requests raised at this table's current visit, with their status")
    @GetMapping
    public List<PublicServiceRequestResponse> list(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken) {
        return serviceRequestService.listForGuest(qr, dinerToken);
    }

    @Operation(summary = "Withdraw your own request, while it is still waiting")
    @PostMapping("/{requestNumber}/cancel")
    public PublicServiceRequestResponse cancel(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String dinerToken,
            @PathVariable String requestNumber) {
        return serviceRequestService.cancelForGuest(qr, dinerToken, requestNumber);
    }
}
