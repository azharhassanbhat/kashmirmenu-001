package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.AnonymousFeedbackRequest;
import com.kashmirmenu.app.dto.request.RewardClaimRequest;
import com.kashmirmenu.app.dto.response.AnonymousFeedbackAckResponse;
import com.kashmirmenu.app.dto.response.PublicLandingResponse;
import com.kashmirmenu.app.dto.response.RewardClaimResponse;
import com.kashmirmenu.app.dto.response.RewardsInfoResponse;
import com.kashmirmenu.app.dto.response.WifiDetailsResponse;
import com.kashmirmenu.app.service.landing.LandingService;
import com.kashmirmenu.app.service.landing.RewardService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The QR landing page. Unauthenticated like the rest of /api/public: the table's QR identifier says which
 * restaurant, and everything here is limited to that restaurant. The landing page itself needs no guest session,
 * so scanning a printed code and looking around works before anyone has given a name.
 *
 * <p>Rewards use their own guest secret in {@value #REWARD_TOKEN_HEADER}, made up by the guest's device; only its
 * hash is stored. Feedback and rewards writes have their own, tighter rate limits (see AuthRateLimitFilter).
 */
@RestController
@RequestMapping("/api/public/tables/{qr}")
@RequiredArgsConstructor
public class PublicLandingController {

    public static final String REWARD_TOKEN_HEADER = "X-Reward-Token";

    private final LandingService landingService;
    private final RewardService rewardService;

    @Operation(summary = "The QR landing page: branding, links, hours, and which actions are enabled")
    @GetMapping("/landing")
    public PublicLandingResponse landing(@PathVariable String qr) {
        return landingService.landing(qr);
    }

    // The password is only ever in this response, and only for a restaurant that switched guest Wi-Fi on.
    // Never cached by the browser or a shared cache.
    @Operation(summary = "Guest Wi-Fi name, security and password (404 when the restaurant offers none)")
    @GetMapping("/wifi")
    public ResponseEntity<WifiDetailsResponse> wifi(@PathVariable String qr) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(landingService.wifi(qr));
    }

    @Operation(summary = "The standard Wi-Fi join QR code as a PNG")
    @GetMapping(value = "/wifi/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> wifiQr(@PathVariable String qr) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(landingService.wifiQr(qr));
    }

    @Operation(summary = "Leave feedback without signing in. Rate limited.")
    @PostMapping("/anonymous-feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public AnonymousFeedbackAckResponse feedback(
            @PathVariable String qr, @Valid @RequestBody AnonymousFeedbackRequest request) {
        return landingService.submitFeedback(qr, request);
    }

    @Operation(summary = "Rewards rules, and the caller's balance if they have joined")
    @GetMapping("/rewards")
    public ResponseEntity<RewardsInfoResponse> rewards(
            @PathVariable String qr,
            @RequestHeader(value = REWARD_TOKEN_HEADER, required = false) String secret) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rewardService.info(qr, secret));
    }

    @Operation(summary = "Start earning rewards on this device (idempotent)")
    @PostMapping("/rewards/join")
    public RewardsInfoResponse join(
            @PathVariable String qr,
            @RequestHeader(value = REWARD_TOKEN_HEADER, required = false) String secret) {
        return rewardService.join(qr, secret);
    }

    @Operation(summary = "Add the points for one served and paid order, once")
    @PostMapping("/rewards/claim")
    public RewardClaimResponse claim(
            @PathVariable String qr,
            @RequestHeader(value = REWARD_TOKEN_HEADER, required = false) String secret,
            @Valid @RequestBody RewardClaimRequest request) {
        return rewardService.claim(qr, secret, request.orderToken());
    }
}
