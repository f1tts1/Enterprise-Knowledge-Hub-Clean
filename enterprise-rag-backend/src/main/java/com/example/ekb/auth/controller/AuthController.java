package com.example.ekb.auth.controller;

import com.example.ekb.auth.dto.LoginRequest;
import com.example.ekb.auth.dto.LoginResponse;
import com.example.ekb.auth.dto.LogoutRequest;
import com.example.ekb.auth.dto.RefreshTokenRequest;
import com.example.ekb.auth.dto.RegisterRequest;
import com.example.ekb.auth.service.AuthService;
import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    // 刷新接口保持匿名访问：access token 过期后，客户端仍需要依靠 refresh token
    // 换取新的登录凭证。真正的有效性判断放在 Redis refresh token 会话中完成。
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    // 退出接口同样不强制要求 access token。这样即使 access token 已经过期，
    // 客户端也可以提交 refresh token 完成服务端撤销，避免“前端已退出但 refresh token 仍可续期”。
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ApiResponse.success(null);
    }
}
