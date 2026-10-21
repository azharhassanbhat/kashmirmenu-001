package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.enums.RestaurantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** Purpose-built response and request shapes for the platform restaurant APIs. */
public final class PlatformRestaurantDtos {

    private PlatformRestaurantDtos() {
    }

    public record Overview(long total, long pending, long approved, long rejected, long suspended) {
    }

    public record Summary(
            Long id,
            String name,
            String ownerName,
            String ownerEmail,
            String email,
            String phone,
            String city,
            String state,
            String country,
            LocalDateTime createdAt,
            RestaurantStatus status,
            /** The rejection or suspension reason matching the current status; null otherwise. */
            String statusReason,
            LocalDateTime reviewedAt) {
    }

    public record Detail(
            Long id,
            String name,
            String slug,
            String ownerName,
            String ownerEmail,
            /** Users have no phone number today, so this is always null; kept so the contract is stable. */
            String ownerPhone,
            String email,
            String phone,
            String address,
            String city,
            String state,
            String country,
            LocalDateTime createdAt,
            RestaurantStatus status,
            String statusReason,
            LocalDateTime reviewedAt,
            String reviewedByName,
            String rejectionReason,
            String suspensionReason,
            long tableCount,
            long menuItemCount,
            /** Restaurants cannot upload documents today, so this is always empty. */
            List<String> documents) {
    }

    /** Body of reject and suspend: the reason is mandatory and is shown to the restaurant owner. */
    public record DecisionRequest(
            @NotBlank(message = "A reason is required")
            @Size(min = 5, max = 500, message = "The reason must be between 5 and 500 characters")
            String reason) {
    }
}
