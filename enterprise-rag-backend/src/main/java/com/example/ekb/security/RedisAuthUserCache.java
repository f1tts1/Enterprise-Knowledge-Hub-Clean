package com.example.ekb.security;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RedisAuthUserCache implements AuthUserCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthUserCache.class);
    private static final String DEFAULT_KEY_PREFIX = "ekb:auth:user:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthUserCacheProperties properties;

    public RedisAuthUserCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AuthUserCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<CachedAuthUser> get(Long userId) {
        if (!properties.isEnabled() || userId == null) {
            return Optional.empty();
        }

        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (!StringUtils.hasText(json)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, CachedAuthUser.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to read auth user cache, userId={}, error={}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(CachedAuthUser user) {
        if (!properties.isEnabled() || user == null || user.id() == null) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(user);
            redisTemplate.opsForValue().set(key(user.id()), json, ttl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to write auth user cache, userId={}, error={}", user.id(), ex.getMessage());
        }
    }

    @Override
    public void evict(Long userId) {
        if (!properties.isEnabled() || userId == null) {
            return;
        }

        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException ex) {
            log.warn("Failed to evict auth user cache, userId={}, error={}", userId, ex.getMessage());
        }
    }

    private String key(Long userId) {
        String keyPrefix = StringUtils.hasText(properties.getKeyPrefix())
                ? properties.getKeyPrefix()
                : DEFAULT_KEY_PREFIX;
        return keyPrefix + userId;
    }

    private Duration ttl() {
        long baseSeconds = Math.max(1, properties.getTtlSeconds());
        long jitterSeconds = Math.max(0, properties.getTtlJitterSeconds());
        long actualSeconds = baseSeconds;
        if (jitterSeconds > 0) {
            actualSeconds += ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
        }
        return Duration.ofSeconds(actualSeconds);
    }
}
