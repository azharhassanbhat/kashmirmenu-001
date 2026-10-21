package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.exception.AccessDeniedException;
import org.junit.jupiter.api.Test;

import static com.kashmirmenu.app.enums.RoleName.*;
import static org.junit.jupiter.api.Assertions.*;

/** Who may manage which staff accounts, and what a staff password must look like. */
class StaffRulesTest {

    @Test
    void anOwnerOrRestaurantAdminMayManageManagersAndEveryFloorAndKitchenRole() {
        for (RoleName target : new RoleName[]{MANAGER, CHEF, WAITER, CASHIER}) {
            assertTrue(StaffRules.canManage(RESTAURANT_OWNER, target), "owner -> " + target);
            assertTrue(StaffRules.canManage(ADMIN, target), "admin -> " + target);
        }
    }

    @Test
    void aManagerMayManageOnlyTheFloorAndTheKitchen() {
        for (RoleName target : new RoleName[]{CHEF, WAITER, CASHIER}) {
            assertTrue(StaffRules.canManage(MANAGER, target), "manager -> " + target);
        }
        assertFalse(StaffRules.canManage(MANAGER, MANAGER), "a manager must not manage managers");
    }

    @Test
    void noRoleCanEverCreateOrChangeAnOwnerAdminPlatformOwnerOrDiner() {
        for (RoleName actor : RoleName.values()) {
            for (RoleName target : new RoleName[]{RESTAURANT_OWNER, ADMIN, PLATFORM_OWNER, CUSTOMER}) {
                assertFalse(StaffRules.canManage(actor, target), actor + " must never manage " + target);
            }
        }
    }

    @Test
    void floorKitchenDinerAndPlatformRolesHaveNoStaffPowersAtAll() {
        for (RoleName actor : new RoleName[]{CHEF, WAITER, CASHIER, CUSTOMER, PLATFORM_OWNER}) {
            for (RoleName target : RoleName.values()) {
                assertFalse(StaffRules.canManage(actor, target), actor + " over " + target);
            }
        }
        assertFalse(StaffRules.canManage(null, WAITER));
        assertFalse(StaffRules.canManage(MANAGER, null));
    }

    @Test
    void handingOutAForbiddenRoleIsRefusedWithAMessageNamingIt() {
        var e = assertThrows(AccessDeniedException.class, () -> StaffRules.requireCanAssign(MANAGER, MANAGER));
        assertTrue(e.getMessage().contains("manager"));
        assertDoesNotThrow(() -> StaffRules.requireCanAssign(RESTAURANT_OWNER, MANAGER));
    }

    @Test
    void aGoodPasswordPasses() {
        assertNull(StaffRules.passwordProblem("Waiter2026", "w@x.com", "Sam"));
        assertNull(StaffRules.passwordProblem("a1".repeat(50), "w@x.com", "Sam"));   // exactly 100
    }

    @Test
    void blankShortLongAndWeakPasswordsAreExplained() {
        assertNotNull(StaffRules.passwordProblem(null, "w@x.com", "Sam"));
        assertNotNull(StaffRules.passwordProblem("   ", "w@x.com", "Sam"));
        assertTrue(StaffRules.passwordProblem("abc12", "w@x.com", "Sam").contains("8"));
        assertNotNull(StaffRules.passwordProblem("a1".repeat(51), "w@x.com", "Sam"));
        assertTrue(StaffRules.passwordProblem("12345678", "w@x.com", "Sam").contains("letter"));
        assertTrue(StaffRules.passwordProblem("abcdefgh", "w@x.com", "Sam").contains("number"));
    }

    @Test
    void aPasswordMayNotBeTheEmailItsLocalPartOrTheName() {
        assertNotNull(StaffRules.passwordProblem("waiter12", "waiter12@x.com", "Sam"));
        assertNotNull(StaffRules.passwordProblem("WAITER12@X.COM", "waiter12@x.com", "Sam"));
        assertNotNull(StaffRules.passwordProblem("sam12345", "w@x.com", "sam12345"));
    }
}
