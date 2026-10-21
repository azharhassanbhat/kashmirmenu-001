package com.kashmirmenu.app.dto.response;

public record RewardRedeemResponse(String memberCode, int pointsSpent, int newBalance, String rewardText) {
}
