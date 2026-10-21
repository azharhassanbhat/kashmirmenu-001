package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.repository.RoleRepository;
import com.kashmirmenu.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Creates the first platform owner, once, without any credential ever living in configuration.
 *
 * <p>The alternatives were worse. Hardcoded credentials are forbidden outright. A password in an
 * environment variable persists a usable secret in the deployment and in every process listing that
 * can read the environment. Auto-promoting the first registered user would mean the platform's most
 * privileged account is handed to whoever signs up first.
 *
 * <p>So: when {@code PLATFORM_OWNER_EMAIL} is set and the platform has no owner, this creates the
 * account <em>disabled</em>, with an unusable password, and logs a single-use claim token. The
 * operator reads that token from the application log — somewhere they must already have access to —
 * and calls {@code POST /api/platform-admin/claim} once to set a password. Only the hash of the token
 * is stored, so even database access does not yield a usable one.
 *
 * <p>Fails closed: with no {@code PLATFORM_OWNER_EMAIL} nothing is created and no platform owner
 * exists. Never creates a second owner, and never resets an existing one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformOwnerBootstrap implements ApplicationRunner {

    /** Long enough that the token cannot be guessed if a log is briefly exposed. */
    private static final int TOKEN_BYTES = 32;

    /** Short-lived on purpose: it is meant to be used immediately after a deployment. */
    static final Duration CLAIM_VALIDITY = Duration.ofHours(24);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final BusinessTime businessTime;

    @Value("${app.platform-owner.email:}")
    private String configuredEmail;

    @Value("${app.platform-owner.name:Platform Owner}")
    private String configuredName;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        String email = configuredEmail == null ? "" : configuredEmail.trim().toLowerCase();

        if (email.isEmpty()) {
            log.info("No PLATFORM_OWNER_EMAIL configured; platform administration is not provisioned.");
            return;
        }

        if (userRepository.existsByRole_Name(RoleName.PLATFORM_OWNER)) {
            log.debug("Platform owner already exists; bootstrap skipped.");
            return;
        }

        if (userRepository.existsByEmail(email)) {
            // Refusing rather than promoting: silently granting platform privileges to an existing
            // restaurant account is exactly the escalation this design exists to prevent.
            log.error("PLATFORM_OWNER_EMAIL '{}' already belongs to an account. Platform owner NOT "
                    + "created. Use an address that is not already registered.", email);
            return;
        }

        Role role = roleRepository.findByName(RoleName.PLATFORM_OWNER)
                .orElseThrow(() -> new IllegalStateException(
                        "The PLATFORM_OWNER role is missing. Migration V24 should have inserted it."));

        String token = newToken();

        User owner = User.builder()
                .name(configuredName)
                .email(email)
                .role(role)
                // Unusable: a random value no one holds, so the account cannot be signed into until
                // it is claimed. Encoded rather than left null because the column is NOT NULL and a
                // recognisable placeholder would be a tell.
                .password(passwordEncoder.encode(newToken()))
                .active(false)
                .build();
        owner.setPlatformClaimTokenHash(passwordEncoder.encode(token));
        owner.setPlatformClaimExpiresAt(businessTime.now().plus(CLAIM_VALIDITY));

        User saved = userRepository.save(owner);

        auditService.record(AuditAction.PLATFORM_OWNER_BOOTSTRAPPED, "USER", saved.getId(),
                "Awaiting claim; token valid for " + CLAIM_VALIDITY.toHours() + " hours");

        // The one place the usable token appears. Logged, never returned from an API.
        log.warn("""

                ================= PLATFORM OWNER BOOTSTRAP =================
                A disabled platform owner account was created for {}.
                Claim it once, within {} hours:

                  POST /api/platform-admin/claim
                  {{"email":"{}","claimToken":"<token below>","password":"<a strong password>"}}

                Claim token: {}

                This token appears only here and only once. After claiming, or after it
                expires, it is useless. Delete or rotate this log line.
                ============================================================
                """, email, CLAIM_VALIDITY.toHours(), email, token);
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
