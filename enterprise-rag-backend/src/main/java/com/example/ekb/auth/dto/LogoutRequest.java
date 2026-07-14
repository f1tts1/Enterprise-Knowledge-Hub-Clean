package com.example.ekb.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 退出登录只需要 refresh token。
 *
 * <p>当前设计不把 access token 写入 Redis 黑名单；普通退出的语义是撤销续期能力，
 * 已签发的短 TTL access token 等待自然过期。
 */
public record LogoutRequest(
        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
