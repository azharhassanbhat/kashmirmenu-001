package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItemRequest {

    @NotBlank(message = "Menu item name is required.")
    private String name;

    private String description;

    @NotNull(message = "Price is required.")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0.")
    private BigDecimal price;

    private String imageUrl;

    @Builder.Default
    private Boolean veg = true;

    @Builder.Default
    private Boolean available = true;

    @Builder.Default
    private Boolean active = true;

    @Builder.Default
    @Min(value = 0, message = "Display order cannot be negative.")
    private Integer displayOrder = 0;

    @Builder.Default
    @Min(value = 0, message = "Preparation time cannot be negative.")
    private Integer preparationTime = 0;

    @NotNull(message = "Category ID is required.")
    private Long categoryId;

    /** Chef's recommendation. null = leave as it is. */
    private Boolean chefPick;

    /** Today's special. null = leave as it is. */
    private Boolean todaysSpecial;

    /**
     * Allergens the restaurant declares for this dish. null = leave as it is (so an older client that
     * does not send the field cannot wipe declared allergens); an empty list = declared none.
     */
    private java.util.List<com.kashmirmenu.app.enums.Allergen> allergens;

    /** Dietary labels. Same convention as {@code allergens}. */
    private java.util.List<com.kashmirmenu.app.enums.DietaryLabel> dietaryLabels;
}