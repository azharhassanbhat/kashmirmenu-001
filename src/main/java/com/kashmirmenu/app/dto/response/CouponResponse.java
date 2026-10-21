package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.DiscountType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class CouponResponse {

    private Long id;

    private Long restaurantId;

    private String restaurantName;

    private String code;

    private String name;

    private String description;

    private DiscountType discountType;

    private BigDecimal discountValue;

    private BigDecimal minimumOrderAmount;

    private BigDecimal maximumDiscount;

    private Integer minimumItems;

    private Integer usageLimit;

    private Integer usedCount;

    private LocalDateTime startDate;

    private LocalDateTime expiryDate;

    private Boolean active;
}