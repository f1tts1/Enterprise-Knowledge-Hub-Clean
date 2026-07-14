package com.example.ekb.auth.service;

import com.example.ekb.auth.dto.LoginRequest;
import com.example.ekb.auth.dto.LoginResponse;
import com.example.ekb.auth.dto.LogoutRequest;
import com.example.ekb.auth.dto.RefreshTokenRequest;
import com.example.ekb.auth.dto.RegisterRequest;
import com.example.ekb.user.dto.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    // 使用 Redis 中仍有效的 refresh token 轮换出一组新 token。
    // 轮换会消费旧 refresh token，避免同一个 refresh token 长期重复使用。
    LoginResponse refresh(RefreshTokenRequest request);

    // 普通退出只撤销当前 refresh token，不处理 access token 黑名单。
    // 这是“短 TTL access token + 可撤销 refresh token”方案的刻意边界。
    void logout(LogoutRequest request);
}
