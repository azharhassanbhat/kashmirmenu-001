package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.LoginRequest;
import com.kashmirmenu.app.dto.request.RegisterRequest;
import com.kashmirmenu.app.dto.request.RegisterRestaurantOwnerRequest;
import com.kashmirmenu.app.dto.response.AuthResponse;
import com.kashmirmenu.app.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(authService.register(request));
    }

    @PostMapping("/register-owner")
    public ResponseEntity<AuthResponse> registerOwner(
            @Valid @RequestBody RegisterRestaurantOwnerRequest request) {

        return ResponseEntity.ok(authService.registerOwner(request));
    }

    /**
     * Signing out is an authenticated call: it revokes the caller's own tokens. It lives under
     * /api/v1/auth/** which is otherwise public, so the authorization is stated here explicitly.
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {

        authService.logout();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {

        return ResponseEntity.ok(
                authService.login(request));
    }

}