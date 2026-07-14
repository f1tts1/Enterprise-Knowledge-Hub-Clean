package com.example.ekb.security;

/**
 * Redis 中保存的 refresh token 会话内容。
 *
 * <p>这里只保存会话定位和过期信息，不保存明文 refresh token，也不保存密码、JWT 等敏感数据。
 */
public record RefreshTokenSession(
        Long userId,
        String username,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond
) {
}
