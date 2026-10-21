package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryRequest {

    @NotBlank(message = "Category name is required.")
    private String name;

    private String description;

    @Builder.Default
    private Integer displayOrder = 0;

    @Builder.Default
    private Boolean active = true;

}