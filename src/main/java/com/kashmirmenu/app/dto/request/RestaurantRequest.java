package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestaurantRequest {

    @NotBlank(message = "Restaurant name is required")
    @Size(max = 100, message = "Restaurant name cannot exceed 100 characters")
    private String name;

    @Email(message = "Invalid email address")
    @Size(max = 100)
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 255)
    private String address;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 100)
    private String country;

    @Size(max = 255)
    private String logo;

    @Size(max = 20)
    private String primaryColor;

    @Size(max = 20)
    private String secondaryColor;
}
