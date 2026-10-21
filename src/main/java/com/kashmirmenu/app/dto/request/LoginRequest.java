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
public class LoginRequest {

    @Email
    @NotBlank
    @Size(max = 150, message = "The email can be up to 150 characters.")
    private String email;

    @NotBlank
    @Size(max = 100, message = "The password can be up to 100 characters.")
    private String password;
}