package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.RoleName;

import java.time.LocalDateTime;

/**
 * A member of the restaurant's team. {@code canManage} is worked out for the signed-in person, so the
 * screen only offers what the server would allow; the server enforces it regardless. Never carries a
 * password or any trace of one.
 */
public record StaffResponse(
        Long id,
        String name,
        String email,
        RoleName role,
        boolean active,
        boolean you,
        boolean canManage,
        LocalDateTime createdAt) {
}
