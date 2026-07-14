package com.example.ekb.security;

/**
 * 刚签发给客户端的明文 refresh token。
 *
 * <p>明文 token 只在响应中返回一次；Redis 中保存的是它的 SHA-256 摘要对应的会话。
 */
public record IssuedRefreshToken(
        String token,
        long expiresInSeconds
) {
}
