package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.RewardRedeemRequest;
import com.kashmirmenu.app.dto.response.RewardMemberResponse;
import com.kashmirmenu.app.dto.response.RewardRedeemResponse;
import com.kashmirmenu.app.service.landing.RewardService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff redeeming a guest's reward. Front-of-house roles only (not the kitchen), always for their own restaurant. */
@RestController
@RequestMapping("/api/v1/rewards")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','WAITER','CASHIER')")
@RequiredArgsConstructor
public class RewardAdminController {

    private final RewardService rewardService;

    @Operation(summary = "A member's balance and whether a reward is due (own restaurant's members only)")
    @GetMapping("/members/{code}")
    public RewardMemberResponse member(@PathVariable String code) {
        return rewardService.lookup(code);
    }

    @Operation(summary = "Redeem one reward for the member whose code the guest read out")
    @PostMapping("/redeem")
    public RewardRedeemResponse redeem(@Valid @RequestBody RewardRedeemRequest request) {
        return rewardService.redeem(request.memberCode());
    }
}
