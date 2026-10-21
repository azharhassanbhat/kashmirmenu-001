package com.kashmirmenu.app.service;

import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.request.CreateStaffRequest;
import com.kashmirmenu.app.dto.request.ResetStaffPasswordRequest;
import com.kashmirmenu.app.dto.request.UpdateStaffRequest;
import com.kashmirmenu.app.dto.response.StaffResponse;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.exception.AccessDeniedException;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.DuplicateResourceException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.RoleRepository;
import com.kashmirmenu.app.repository.UserRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A restaurant's own team: creating staff accounts, changing a role, switching an account off and on,
 * and resetting a forgotten password. Who may do what is decided by {@link StaffRules} and enforced
 * here on the server; the screen merely hides what would be refused.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Every lookup is limited to the caller's own restaurant; another restaurant's account answers
 *       "not found", exactly like one that does not exist.</li>
 *   <li>Nobody can create or change an account above their own authority, and nobody can change their
 *       own account here (so nobody can lock themselves out or promote themselves).</li>
 *   <li>Deactivating, changing a role or resetting a password signs that person out everywhere.</li>
 *   <li>Passwords are stored only as hashes, never logged, never returned and never written to the
 *       audit trail.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StaffService {

    /** The accounts a restaurant sees as its team. Diners and platform accounts are never listed. */
    private static final Set<RoleName> TEAM = EnumSet.of(
            RoleName.RESTAURANT_OWNER, RoleName.ADMIN, RoleName.MANAGER,
            RoleName.CHEF, RoleName.WAITER, RoleName.CASHIER);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final BusinessTime businessTime;

    @Transactional(readOnly = true)
    public List<StaffResponse> list() {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        User actor = currentUserService.getCurrentUser();
        return users.findByRestaurantIdOrderByNameAsc(restaurantId).stream()
                .filter(u -> u.getRole() != null && TEAM.contains(u.getRole().getName()))
                .map(u -> toResponse(u, actor))
                .toList();
    }

    public StaffResponse create(CreateStaffRequest request) {
        User actor = currentUserService.getCurrentUser();
        Restaurant restaurant = currentUserService.getCurrentRestaurant();

        StaffRules.requireCanAssign(actor.getRole().getName(), request.role());

        String email = request.email().strip();
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new BadRequestException("Please enter the person's name.");
        }
        if (users.existsByEmail(email)) {
            throw new DuplicateResourceException("That email address is already registered.");
        }
        String problem = StaffRules.passwordProblem(request.password(), email, name);
        if (problem != null) {
            throw new BadRequestException(problem);
        }

        Role role = roles.findByName(request.role())
                .orElseThrow(() -> new IllegalStateException(request.role() + " role not found."));

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setRestaurant(restaurant);
        user.setActive(true);
        User saved = users.save(user);

        auditService.record(AuditAction.STAFF_CREATED, "USER", saved.getId(), "Role " + request.role());
        return toResponse(saved, actor);
    }

    public StaffResponse update(Long id, UpdateStaffRequest request) {
        User actor = currentUserService.getCurrentUser();
        User target = ownTeamMember(id);
        refuseSelf(actor, target);

        RoleName actorRole = actor.getRole().getName();
        RoleName targetRole = target.getRole().getName();
        if (!StaffRules.canManage(actorRole, targetRole)) {
            throw new AccessDeniedException("You are not allowed to change this account.");
        }

        if (request.name() != null) {
            String name = request.name().strip();
            if (name.isEmpty()) {
                throw new BadRequestException("The name cannot be blank.");
            }
            target.setName(name);
        }

        if (request.role() != null && request.role() != targetRole) {
            StaffRules.requireCanAssign(actorRole, request.role());
            Role role = roles.findByName(request.role())
                    .orElseThrow(() -> new IllegalStateException(request.role() + " role not found."));
            target.setRole(role);
            signOutEverywhere(target);
            auditService.record(AuditAction.STAFF_UPDATED, "USER", target.getId(),
                    "Role " + targetRole + " to " + request.role());
        }

        if (request.active() != null && !request.active().equals(target.getActive())) {
            target.setActive(request.active());
            if (!request.active()) {
                signOutEverywhere(target);
            }
            auditService.record(
                    request.active() ? AuditAction.STAFF_REACTIVATED : AuditAction.STAFF_DEACTIVATED,
                    "USER", target.getId(), "Role " + target.getRole().getName());
        }

        return toResponse(users.save(target), actor);
    }

    public void resetPassword(Long id, ResetStaffPasswordRequest request) {
        User actor = currentUserService.getCurrentUser();
        User target = ownTeamMember(id);
        refuseSelf(actor, target);

        if (!StaffRules.canManage(actor.getRole().getName(), target.getRole().getName())) {
            throw new AccessDeniedException("You are not allowed to change this account.");
        }
        String problem = StaffRules.passwordProblem(request.password(), target.getEmail(), target.getName());
        if (problem != null) {
            throw new BadRequestException(problem);
        }

        target.setPassword(passwordEncoder.encode(request.password()));
        signOutEverywhere(target);
        users.save(target);
        // Deliberately no detail: the new password must never reach the audit trail.
        auditService.record(AuditAction.STAFF_PASSWORD_RESET, "USER", target.getId(), "Password reset");
    }

    // ---------------------------------------------------------------- internals

    /** Same answer for another restaurant's account, a diner, or a number that does not exist. */
    private User ownTeamMember(Long id) {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        return users.findById(id)
                .filter(u -> u.getRestaurant() != null && u.getRestaurant().getId().equals(restaurantId))
                .filter(u -> u.getRole() != null && TEAM.contains(u.getRole().getName()))
                .orElseThrow(() -> new ResourceNotFoundException("Staff member not found."));
    }

    private static void refuseSelf(User actor, User target) {
        if (actor.getId().equals(target.getId())) {
            throw new BadRequestException(
                    "You cannot change your own account here. Ask the owner or a manager to do it.");
        }
    }

    /** Every token issued before this moment stops working, on every device. */
    private void signOutEverywhere(User user) {
        user.setTokensValidFrom(businessTime.now());
    }

    private static StaffResponse toResponse(User u, User viewer) {
        return new StaffResponse(
                u.getId(),
                u.getName(),
                u.getEmail(),
                u.getRole().getName(),
                Boolean.TRUE.equals(u.getActive()),
                u.getId().equals(viewer.getId()),
                !u.getId().equals(viewer.getId())
                        && StaffRules.canManage(viewer.getRole().getName(), u.getRole().getName()),
                u.getCreatedAt());
    }
}
