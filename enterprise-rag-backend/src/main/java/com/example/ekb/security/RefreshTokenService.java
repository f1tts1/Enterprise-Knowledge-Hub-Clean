package com.example.ekb.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.user.entity.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RefreshTokenService {

    private static final String DEFAULT_KEY_PREFIX = "ekb:auth:refresh:";
    private static final String DEFAULT_USER_SESSIONS_KEY_PREFIX = "ekb:auth:user-refresh:";
    private static final int MIN_TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RefreshTokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RefreshTokenProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public IssuedRefreshToken issue(User user) {
        // 生成不可预测的随机字符串给客户端；服务端只使用它的摘要作为 Redis key，
        // 即使 Redis 数据被查看，也不会直接暴露可用的 refresh token。
        String token = generateToken();
        String tokenId = tokenId(token);
        Instant now = Instant.now();
        long expiresInSeconds = Math.max(1, properties.getExpiresInSeconds());
        RefreshTokenSession session = new RefreshTokenSession(
                user.getId(),
                user.getUsername(),
                now.getEpochSecond(),
                now.plusSeconds(expiresInSeconds).getEpochSecond()
        );

        try {
            String json = objectMapper.writeValueAsString(session);
            Duration ttl = Duration.ofSeconds(expiresInSeconds);
            // 单 token 会话用于 refresh/logout 精确定位；用户集合为后续“退出全部设备”
            // 或账号异常时批量撤销 refresh token 预留索引。
            redisTemplate.opsForValue().set(refreshKey(tokenId), json, ttl);
            redisTemplate.opsForSet().add(userSessionsKey(user.getId()), tokenId);
            redisTemplate.expire(userSessionsKey(user.getId()), ttl);
            return new IssuedRefreshToken(token, expiresInSeconds);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, "Failed to store refresh token");
        }
    }

    public Optional<RefreshTokenSession> consume(String refreshToken) {
        String tokenId = tokenId(refreshToken);
        String key = refreshKey(tokenId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return Optional.empty();
            }

            RefreshTokenSession session = objectMapper.readValue(json, RefreshTokenSession.class);
            // 刷新采用轮换模型：只要 refresh token 被消费，无论后续用户状态校验是否通过，
            // 旧 token 都会立即撤销，降低重复提交或泄露后的可用窗口。
            revokeByTokenId(tokenId, session.userId());
            if (session.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, "Failed to read refresh token");
        }
    }

    public void revoke(String refreshToken) {
        String tokenId = tokenId(refreshToken);
        try {
            // logout 只拿到 refresh token 明文，因此先读取会话以定位 userId，
            // 再同时删除单 token key 和用户集合中的 tokenId。
            String json = redisTemplate.opsForValue().get(refreshKey(tokenId));
            Long userId = null;
            if (StringUtils.hasText(json)) {
                userId = objectMapper.readValue(json, RefreshTokenSession.class).userId();
            }
            revokeByTokenId(tokenId, userId);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE, "Failed to revoke refresh token");
        }
    }

    private void revokeByTokenId(String tokenId, Long userId) {
        redisTemplate.delete(refreshKey(tokenId));
        if (userId != null) {
            redisTemplate.opsForSet().remove(userSessionsKey(userId), tokenId);
        }
    }

    private String generateToken() {
        // Base64URL without padding 适合放入 JSON/HTTP，不需要额外转义。
        byte[] bytes = new byte[Math.max(MIN_TOKEN_BYTES, properties.getTokenBytes())];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenId(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        try {
            // Redis key 使用摘要而不是明文 token：这是很小的安全成本，却能避免
            // Redis keyspace 本身泄露可直接使用的长期凭证。
            // 将 refreshToken 明文转化为：
            //-> SHA-256 摘要
            //-> 十六进制字符串
            //-> 作为 Redis key 的一部分
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String refreshKey(String tokenId) {
        return prefix(properties.getKeyPrefix(), DEFAULT_KEY_PREFIX) + tokenId;
    }

    private String userSessionsKey(Long userId) {
        return prefix(properties.getUserSessionsKeyPrefix(), DEFAULT_USER_SESSIONS_KEY_PREFIX) + userId;
    }

    private String prefix(String configuredPrefix, String defaultPrefix) {
        return StringUtils.hasText(configuredPrefix) ? configuredPrefix : defaultPrefix;
    }
}
