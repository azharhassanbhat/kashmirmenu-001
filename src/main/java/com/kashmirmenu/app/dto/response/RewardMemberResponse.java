package com.kashmirmenu.app.dto.response;

/** What staff see when they look a member up before redeeming: the balance and whether a reward is due. */
public record RewardMemberResponse(
        String memberCode, int balance, int redeemPoints, int pointsToGo, boolean canRedeem, String rewardText) {
}
