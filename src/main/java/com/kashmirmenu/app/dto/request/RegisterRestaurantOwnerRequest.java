package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRestaurantOwnerRequest {

    @NotBlank(message = "Restaurant name is required")
    private String restaurantName;

    @Email(message = "Invalid restaurant email")
    private String restaurantEmail;

    private String restaurantPhone;

    @NotBlank(message = "Owner name is required")
    private String ownerName;

    @Email(message = "Invalid owner email")
    @NotBlank(message = "Owner email is required")
    private String ownerEmail;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    private String password;
}