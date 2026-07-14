package com.example.ekb.knowledge.access;

import java.time.LocalDateTime;

import com.example.ekb.knowledge.entity.KnowledgeBase;

/**
 * Redis 中保存的知识库访问校验快照。
 *
 * <p>它不是完整业务缓存层，只服务于“当前用户是否拥有这个知识库”这个高频判断。
 * 保留名称、描述等字段，是为了详情接口命中缓存时可以直接返回，不需要再查一次 MySQL。
 */
public record CachedKnowledgeBaseAccess(
        Long id,
        Long ownerUserId,
        String name,
        String description,
        String visibility,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer isDeleted
) {

    private static final int DELETED = 1;

    public static CachedKnowledgeBaseAccess from(KnowledgeBase knowledgeBase) {
        return new CachedKnowledgeBaseAccess(
                knowledgeBase.getId(),
                knowledgeBase.getOwnerUserId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getVisibility(),
                knowledgeBase.getStatus(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt(),
                knowledgeBase.getIsDeleted()
        );
    }

    public boolean isAccessibleBy(Long currentUserId) {
        return id != null
                && ownerUserId != null
                && ownerUserId.equals(currentUserId)
                && !Integer.valueOf(DELETED).equals(isDeleted);
    }

    public KnowledgeBase toEntity() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setOwnerUserId(ownerUserId);
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        knowledgeBase.setVisibility(visibility);
        knowledgeBase.setStatus(status);
        knowledgeBase.setCreatedAt(createdAt);
        knowledgeBase.setUpdatedAt(updatedAt);
        knowledgeBase.setIsDeleted(isDeleted);
        return knowledgeBase;
    }
}
