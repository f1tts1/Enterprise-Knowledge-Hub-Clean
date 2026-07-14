package com.example.ekb.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh Token 的 Redis 存储配置。
 *
 * <p>这些配置独立于 JWT 配置：access token 负责短期 API 访问，
 * refresh token 负责可撤销的长期登录会话。
 */
@ConfigurationProperties(prefix = "app.security.refresh-token")
public class RefreshTokenProperties {

    // 保存单个 refresh token 会话。实际 key 会拼接 sha256(refreshToken)，不保存明文 token。
    private String keyPrefix = "ekb:auth:refresh:";

    // 保存某个用户当前有哪些 refresh token，会为“退出全部设备”预留基础。
    private String userSessionsKeyPrefix = "ekb:auth:user-refresh:";

    // 默认 14 天。它决定“多久不刷新就需要重新登录”。
    private long expiresInSeconds = 1209600;

    // 随机 token 的字节数。32 字节经 Base64URL 编码后已经足够本项目使用。
    private int tokenBytes = 32;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getUserSessionsKeyPrefix() {
        return userSessionsKeyPrefix;
    }

    public void setUserSessionsKeyPrefix(String userSessionsKeyPrefix) {
        this.userSessionsKeyPrefix = userSessionsKeyPrefix;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }

    public int getTokenBytes() {
        return tokenBytes;
    }

    public void setTokenBytes(int tokenBytes) {
        this.tokenBytes = tokenBytes;
    }
}
