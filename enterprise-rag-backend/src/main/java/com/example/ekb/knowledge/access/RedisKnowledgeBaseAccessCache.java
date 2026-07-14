package com.example.ekb.knowledge.access;

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
public class RedisKnowledgeBaseAccessCache implements KnowledgeBaseAccessCache {

    private static final Logger log = LoggerFactory.getLogger(RedisKnowledgeBaseAccessCache.class);
    private static final String DEFAULT_KEY_PREFIX = "ekb:kb:meta:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeBaseAccessCacheProperties properties;

    public RedisKnowledgeBaseAccessCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            KnowledgeBaseAccessCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<CachedKnowledgeBaseAccess> get(Long knowledgeBaseId) {
        if (!properties.isEnabled() || knowledgeBaseId == null) {
            return Optional.empty();
        }

        try {
            String json = redisTemplate.opsForValue().get(key(knowledgeBaseId));
            if (!StringUtils.hasText(json)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, CachedKnowledgeBaseAccess.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to read knowledge base access cache, kbId={}, error={}",
                    knowledgeBaseId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(CachedKnowledgeBaseAccess knowledgeBase) {
        if (!properties.isEnabled() || knowledgeBase == null || knowledgeBase.id() == null) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(knowledgeBase);
            redisTemplate.opsForValue().set(key(knowledgeBase.id()), json, ttl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to write knowledge base access cache, kbId={}, error={}",
                    knowledgeBase.id(), ex.getMessage());
        }
    }

    @Override
    public void evict(Long knowledgeBaseId) {
        if (!properties.isEnabled() || knowledgeBaseId == null) {
            return;
        }

        try {
            redisTemplate.delete(key(knowledgeBaseId));
        } catch (RuntimeException ex) {
            log.warn("Failed to evict knowledge base access cache, kbId={}, error={}",
                    knowledgeBaseId, ex.getMessage());
        }
    }

    private String key(Long knowledgeBaseId) {
        String keyPrefix = StringUtils.hasText(properties.getKeyPrefix())
                ? properties.getKeyPrefix()
                : DEFAULT_KEY_PREFIX;
        return keyPrefix + knowledgeBaseId;
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
