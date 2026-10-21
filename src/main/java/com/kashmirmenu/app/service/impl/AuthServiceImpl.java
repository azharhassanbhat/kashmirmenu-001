package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.security.LoginAttemptTracker;

import com.kashmirmenu.app.dto.request.LoginRequest;
import com.kashmirmenu.app.dto.request.RegisterRequest;
import com.kashmirmenu.app.dto.request.RegisterRestaurantOwnerRequest;
import com.kashmirmenu.app.dto.response.AuthResponse;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.exception.DuplicateResourceException;
import com.kashmirmenu.app.metrics.AuthenticationMetrics;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.repository.RoleRepository;
import com.kashmirmenu.app.repository.UserRepository;
import com.kashmirmenu.app.security.CustomUserDetails;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.config.RestaurantApprovalProperties;
import com.kashmirmenu.app.security.jwt.JwtService;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.AuthService;
import com.kashmirmenu.app.util.AfterCommit;
import com.kashmirmenu.app.util.SlugUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private final RestaurantRepository restaurantRepository;
    private final SlugUtil slugUtil;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final AuthenticationMetrics authenticationMetrics;
    private final BusinessTime businessTime;
    private final RestaurantApprovalProperties approvalProperties;
    private final com.kashmirmenu.app.platform.PlatformEventPublisher platformEvents;
    private final com.kashmirmenu.app.config.RateLimitProperties rateLimitProperties;

    /** Failed sign-ins per account; see {@link LoginAttemptTracker}. Built lazily from the configured limits. */
    private volatile LoginAttemptTracker loginAttempts;

    private LoginAttemptTracker loginAttempts() {
        LoginAttemptTracker t = loginAttempts;
        if (t == null) {
            synchronized (this) {
                t = loginAttempts;
                if (t == null) {
                    t = new LoginAttemptTracker(rateLimitProperties.getLoginFailureLimit(),
                            rateLimitProperties.getLoginFailureWindowSeconds());
                    loginAttempts = t;
                }
            }
        }
        return t;
    }

    @Override
    public AuthResponse register(RegisterRequest request) {

        return registerUser(request, RoleName.CUSTOMER);
    }

    @Override
    public AuthResponse registerOwner(RegisterRestaurantOwnerRequest request) {

        log.info("Registering a new restaurant owner");

        validateEmail(request.getOwnerEmail());

        Role role = getRole(RoleName.RESTAURANT_OWNER);

        // Create Restaurant
        Restaurant restaurant = createRestaurant(request);

        Restaurant savedRestaurant =
                restaurantRepository.save(restaurant);

        // Create Owner
        User user = createUser(request, role);

        user.setRestaurant(savedRestaurant);

        User savedUser = saveUser(user);

        // Link Restaurant → Owner
        savedRestaurant.setOwner(savedUser);

        restaurantRepository.save(savedRestaurant);

        String token = jwtService.generateToken(
                new CustomUserDetails(savedUser));

        if (savedRestaurant.getApprovalStatus() == com.kashmirmenu.app.enums.RestaurantStatus.PENDING) {
            platformEvents.restaurantRegistered(savedRestaurant);
        }

        // After commit, so a registration that fails and rolls back leaves no "registered" line.
        String registeredEmail = savedUser.getEmail();
        String registeredDetail = "Restaurant " + savedRestaurant.getId() + " registered as "
                + savedRestaurant.getApprovalStatus();
        AfterCommit.run(() ->
                auditService.recordForEmail(AuditAction.RESTAURANT_REGISTERED, registeredEmail, registeredDetail));

        log.info("Restaurant {} registered successfully with owner (user id {})",
                savedRestaurant.getId(),
                savedUser.getId());

        return buildAuthResponse(token);
    }

    @Override
    public void logout() {

        User user = currentUserService.getCurrentUser();

        // Moving the watermark forward invalidates every token minted before this instant, for this
        // user only. No denylist to store or expire, and it also covers tokens on other devices.
        user.setTokensValidFrom(businessTime.now());
        userRepository.save(user);

        auditService.record(AuditAction.LOGGED_OUT, "USER", user.getId(), "Tokens revoked");

        log.info("User {} signed out; tokens issued before now are no longer accepted", user.getId());
    }

    private AuthResponse registerUser(
            RegisterRequest request,
            RoleName roleName) {

        log.info("Registering a new {}", roleName);

        validateEmail(request);

        Role role = getRole(roleName);

        User user = createUser(request, role);

        User savedUser = saveUser(user);

        String token = jwtService.generateToken(
                new CustomUserDetails(savedUser));

        log.info("{} registered successfully (user id {})", roleName, savedUser.getId());

        return buildAuthResponse(token);
    }

    @Override
    public AuthResponse login(LoginRequest request) {

        log.debug("Login request received");

        String attemptKey = LoginAttemptTracker.keyFor(request.getEmail());
        if (loginAttempts().isLocked(attemptKey)) {
            // Refused without checking the password at all, so a guesser learns nothing from the answer.
            auditService.recordForEmail(AuditAction.LOGIN_FAILED, request.getEmail(), "Refused: too many failed attempts");
            authenticationMetrics.recordBadCredentials();
            throw new com.kashmirmenu.app.exception.TooManyRequestsException(
                    "Too many failed sign-in attempts. Please wait a few minutes and try again.",
                    rateLimitProperties.getLoginFailureWindowSeconds());
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );
        } catch (AuthenticationException ex) {
            // Recorded before rethrowing, and in its own transaction, so a failed attempt leaves a
            // trace even though this path ends in an exception.
            // Audited for investigation (who, when), counted for alerting (how many). The counter
            // carries no identifier; the audit row is where the detail lives.
            auditService.recordForEmail(AuditAction.LOGIN_FAILED, request.getEmail(),
                    "Authentication failed");
            authenticationMetrics.recordBadCredentials();
            loginAttempts().recordFailure(attemptKey);
            throw ex;
        }
        loginAttempts().reset(attemptKey);

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {

                    // Not logged with the email: the value is attacker-controlled and logging it
                    // would make the log a user-enumeration oracle.
                    return new ResourceNotFoundException(
                            "User not found.");
                });

        String token = jwtService.generateToken(
                new CustomUserDetails(user));

        auditService.recordForEmail(AuditAction.LOGIN_SUCCEEDED, user.getEmail(),
                "Signed in as " + user.getRole().getName());

        log.info("User {} logged in successfully", user.getId());

        return buildAuthResponse(token);
    }

    private void validateEmail(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {

            log.warn("Registration refused: the email is already in use");

            throw new DuplicateResourceException(
                    "Email already registered.");
        }
    }

    private void validateEmail(String email) {

        if (userRepository.existsByEmail(email)) {

            log.warn("Registration refused: the email is already in use");

            throw new DuplicateResourceException(
                    "Email already registered.");
        }
    }

    private Role getRole(RoleName roleName) {

        return roleRepository.findByName(roleName)
                .orElseThrow(() -> {

                    log.error("{} role not found.", roleName);

                    return new ResourceNotFoundException(
                            roleName + " role not found.");
                });
    }

    private User createUser(RegisterRequest request, Role role) {

        User user = new User();

        user.setName(request.getName());
        user.setEmail(request.getEmail());

        user.setPassword(
                passwordEncoder.encode(request.getPassword()));

        user.setRole(role);
        user.setActive(true);

        return user;
    }

    private User createUser(
            RegisterRestaurantOwnerRequest request,
            Role role) {

        User user = new User();

        user.setName(request.getOwnerName());
        user.setEmail(request.getOwnerEmail());

        user.setPassword(
                passwordEncoder.encode(request.getPassword()));

        user.setRole(role);
        user.setActive(true);

        return user;
    }

    private User saveUser(User user) {

        User savedUser = userRepository.save(user);

        log.debug("User saved with id={}", savedUser.getId());

        return savedUser;
    }

    private AuthResponse buildAuthResponse(String token) {

        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .build();
    }

    private Restaurant createRestaurant(RegisterRestaurantOwnerRequest request) {

        Restaurant restaurant = new Restaurant();

        restaurant.setName(request.getRestaurantName());
        restaurant.setSlug(slugUtil.generateUniqueSlug(request.getRestaurantName()));
        restaurant.setEmail(request.getRestaurantEmail());
        restaurant.setPhone(request.getRestaurantPhone());

        restaurant.setActive(true);
        restaurant.setApprovalStatus(approvalProperties.initialStatus());

        return restaurant;
    }
}