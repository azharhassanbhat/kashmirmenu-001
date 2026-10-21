package com.kashmirmenu.app.platform;

public interface PlatformClaimService {

    /**
     * Activates the bootstrapped platform owner and sets its password. Succeeds at most once: the
     * token is cleared, so a replay finds nothing to claim.
     */
    void claim(PlatformClaimRequest request);
}
