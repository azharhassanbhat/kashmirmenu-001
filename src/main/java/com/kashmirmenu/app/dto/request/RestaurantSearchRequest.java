package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantSearchRequest {

    private String name;

    private String city;

    private Boolean active;

    @Builder.Default
    @Min(value = 0, message = "Page must be greater than or equal to 0")
    private int page = 0;

    @Builder.Default
    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 100, message = "Size cannot exceed 100")
    private int size = 10;

    @Builder.Default
    private String sortBy = "id";

    @Builder.Default
    @Pattern(
            regexp = "asc|desc",
            flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "Direction must be asc or desc"
    )
    private String direction = "asc";
}