package com.kashmirmenu.app.dto.response;

import lombok.Data;

@Data
public class RestaurantTableResponse {

    private Long id;

    private String tableCode;

    private String tableName;

    private Integer capacity;

    private String qrIdentifier;

    private Boolean active;
}