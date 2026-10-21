package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.LoginRequest;
import com.kashmirmenu.app.dto.request.RegisterRequest;
import com.kashmirmenu.app.dto.request.RegisterRestaurantOwnerRequest;
import com.kashmirmenu.app.dto.response.AuthResponse;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse registerOwner(RegisterRestaurantOwnerRequest request);

    AuthResponse login(LoginRequest request);

    /**
     * Revokes every token issued to the signed-in user before now. Tokens are stateless, so this
     * is what makes "sign out" mean something on the server rather than only in the browser.
     */
    void logout();

}