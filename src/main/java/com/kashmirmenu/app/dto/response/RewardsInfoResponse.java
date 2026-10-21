package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** The rewards card: the rules for everyone, and the caller's own balance when they have joined. */
public record RewardsInfoResponse(
        boolean enabled,
        BigDecimal spendPerPoint,
        int redeemPoints,
        String rewardText,
        String terms,
        Member member) {

    public record Member(String code, int balance, int pointsToGo, boolean canRedeem, List<Entry> history) {
    }

    public record Entry(String kind, int points, String note, LocalDateTime at) {
    }

    public static RewardsInfoResponse off() {
        return new RewardsInfoResponse(false, null, 0, null, null, null);
    }
}
