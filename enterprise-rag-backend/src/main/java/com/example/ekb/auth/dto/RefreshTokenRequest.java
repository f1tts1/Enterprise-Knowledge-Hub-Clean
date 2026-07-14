package com.example.ekb.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * refresh token 是服务端可撤销的长周期登录会话凭证。
 *
 * <p>客户端不能用它直接访问业务 API，只能调用刷新接口换取新的短周期 access token。
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
