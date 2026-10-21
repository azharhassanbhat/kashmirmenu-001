package com.kashmirmenu.app.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryResponse {

    private Long id;

    private String name;

    private String description;

    private Integer displayOrder;

    private Boolean active;

    private Long restaurantId;

    private String restaurantName;
}