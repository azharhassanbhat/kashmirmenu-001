package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class UpdateCouponRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private DiscountType discountType;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal discountValue;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal minimumOrderAmount;

    private BigDecimal maximumDiscount;

    /** Fewest dishes in the order. 0 for no requirement. A count, not an amount. */
    @Min(0)
    private Integer minimumItems;

    @NotNull
    @Min(0)
    private Integer usageLimit;

    @NotNull
    private LocalDateTime startDate;

    @NotNull
    private LocalDateTime expiryDate;

    @NotNull
    private Boolean active;
}