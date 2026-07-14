package com.example.ekb.auth.dto;

import com.example.ekb.user.dto.UserResponse;

/**
 * 登录和刷新 token 共用同一个响应结构。
 *
 * <p>accessToken 是短 TTL JWT，仍由客户端放入 Authorization header。
 * refreshToken 是长 TTL 随机凭证，只用于换发新 token 或退出登录时撤销会话。
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        long refreshExpiresInSeconds,
        UserResponse user
) {
}
