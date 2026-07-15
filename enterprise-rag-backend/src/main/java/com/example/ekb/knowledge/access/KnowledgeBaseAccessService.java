package com.example.ekb.knowledge.access;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.knowledge.entity.KnowledgeBase;
import com.example.ekb.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseAccessService {

    private static final int NOT_DELETED = 0;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseAccessCache accessCache;

    public KnowledgeBaseAccessService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseAccessCache accessCache
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.accessCache = accessCache;
    }

    public KnowledgeBase requireOwned(Long currentUserId, Long knowledgeBaseId) {
        CachedKnowledgeBaseAccess cached = accessCache.get(knowledgeBaseId).orElse(null);
        if (cached != null) {
            if (cached.isAccessibleBy(currentUserId)) {
                return cached.toEntity();
            }
            // 即使命中的是其它用户的知识库，也保持 not found 风格响应，
            // 避免通过权限错误泄露资源存在性。
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getIsDeleted, NOT_DELETED)
                .last("LIMIT 1"));
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        accessCache.put(CachedKnowledgeBaseAccess.from(knowledgeBase));
        if (!currentUserId.equals(knowledgeBase.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return knowledgeBase;
    }

    /**
     * 写路径绕过正向缓存并锁定知识库行。上传文档与删除知识库在各自事务里调用该方法，
     * 共享同一把数据库行锁，避免“无文档检查”和“新文档插入”之间的竞态。
     */
    public KnowledgeBase requireOwnedForWrite(Long currentUserId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOwnedForUpdate(currentUserId, knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return knowledgeBase;
    }

    public void evict(Long knowledgeBaseId) {
        accessCache.evict(knowledgeBaseId);
    }
}
