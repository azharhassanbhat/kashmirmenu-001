package com.kashmirmenu.app.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItemResponse {

    private Long id;

    private String name;

    private String description;

    private BigDecimal price;

    private String imageUrl;

    private Boolean veg;

    private Boolean available;

    private Boolean active;

    private Integer displayOrder;

    private Integer preparationTime;

    private Long restaurantId;

    private String restaurantName;

    private Long categoryId;

    private String categoryName;
    private Boolean chefPick;
    private Boolean todaysSpecial;
    /** True once the restaurant has declared allergens (even "none"); false means it has not said. */
    private Boolean allergenInfoProvided;
    private java.util.List<TagResponse> allergens;
    private java.util.List<TagResponse> dietaryLabels;
    /** Best sellers over the last 30 days of real orders. Only set on the public menu. */
    private Boolean popular;
    /** Portion sizes, add-ons, spice level. Only set on the public menu; admins use the options endpoint. */
    private java.util.List<OptionGroupResponse> optionGroups;
}