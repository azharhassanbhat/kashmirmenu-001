package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.metrics.AuthenticationMetrics;
import com.kashmirmenu.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PlatformClaimServiceImpl implements PlatformClaimService {

    /** One message for every failure mode, so the endpoint reveals nothing about which one it was. */
    private static final String REFUSED = "That claim could not be completed.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AuthenticationMetrics authenticationMetrics;
    private final BusinessTime businessTime;

    @Override
    public void claim(PlatformClaimRequest request) {

        String email = request.email().trim().toLowerCase();

        User candidate = userRepository.findByEmail(email).orElse(null);

        // Every rejection below returns the same message and records the same audit action. An
        // attacker who guesses the address must not be able to tell it apart from a wrong token,
        // an already-claimed account or an expired one.
        if (candidate == null
                || candidate.getRole().getName() != RoleName.PLATFORM_OWNER
                || candidate.getPlatformClaimTokenHash() == null
                || candidate.getPlatformClaimExpiresAt() == null
                || candidate.getPlatformClaimExpiresAt().isBefore(businessTime.now())
                || !passwordEncoder.matches(request.claimToken(), candidate.getPlatformClaimTokenHash())) {

            auditService.recordForEmail(AuditAction.PLATFORM_OWNER_CLAIM_FAILED, email,
                    "Claim refused");
            authenticationMetrics.recordClaimRefused();
            log.warn("Platform owner claim refused for a supplied address.");
            throw new BadRequestException(REFUSED);
        }

        candidate.setPassword(passwordEncoder.encode(request.password()));
        candidate.setActive(true);

        // Consumed: the token is gone, so the claim cannot be replayed even with the same value.
        candidate.setPlatformClaimTokenHash(null);
        candidate.setPlatformClaimExpiresAt(null);

        // Any token minted before this moment is refused, which matters if the disabled account was
        // somehow signed into before being claimed.
        candidate.setTokensValidFrom(businessTime.now());

        userRepository.save(candidate);

        auditService.recordForEmail(AuditAction.PLATFORM_OWNER_CLAIMED, email,
                "Platform owner account activated");
        log.warn("Platform owner account claimed and activated (user id {}).", candidate.getId());
    }
}
