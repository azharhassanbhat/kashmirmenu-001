package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.concurrency.LockRetry;
import com.kashmirmenu.app.dto.request.JoinTableRequest;
import com.kashmirmenu.app.dto.response.DinerJoinResponse;
import com.kashmirmenu.app.dto.response.DinerMeResponse;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.service.TableSessionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Guests joining a table. Unauthenticated by design: the table's QR code gets a guest in, and the
 * secret returned here is what identifies them afterwards.
 */
@RestController
@RequestMapping("/api/public/tables/{qr}")
@RequiredArgsConstructor
public class PublicDinerController {

    /** The header a guest's client sends on every request made as that guest. */
    public static final String TOKEN_HEADER = "X-Diner-Token";

    private final TableSessionService tableSessionService;
    private final LockRetry retry;

    @Operation(summary = "Join the table with a name; returns this guest's session token (shown once)")
    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    public DinerJoinResponse join(
            @PathVariable String qr,
            @Valid @RequestBody JoinTableRequest request) {

        TableSessionService.Joined joined = retry.call("join", () -> tableSessionService.join(qr, request.name()));

        return new DinerJoinResponse(
                joined.token(),
                joined.diner().getDisplayName(),
                joined.diner().getTableSession().getVisitCode());
    }

    @Operation(summary = "Who the X-Diner-Token belongs to, if the session is still open")
    @GetMapping("/me")
    public DinerMeResponse me(
            @PathVariable String qr,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        DinerSession diner = tableSessionService.resolve(qr, token);

        return new DinerMeResponse(
                diner.getDisplayName(),
                diner.getTableSession().getVisitCode());
    }
}
