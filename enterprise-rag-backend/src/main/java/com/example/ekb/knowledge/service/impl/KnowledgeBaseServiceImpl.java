package com.example.ekb.knowledge.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.common.response.PageResponse;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.ekb.knowledge.dto.KnowledgeBaseResponse;
import com.example.ekb.knowledge.entity.KnowledgeBase;
import com.example.ekb.knowledge.mapper.KnowledgeBaseMapper;
import com.example.ekb.knowledge.service.KnowledgeBaseService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final String VISIBILITY_PRIVATE = "PRIVATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final int NOT_DELETED = 0;
    private static final int DELETED = 1;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;

    public KnowledgeBaseServiceImpl(
            KnowledgeBaseMapper knowledgeBaseMapper,
            DocumentMapper documentMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
    }

    @Override
    @Transactional
    public KnowledgeBaseResponse create(Long currentUserId, CreateKnowledgeBaseRequest request) {
        String name = request.name().trim();
        // V1 中每个知识库都是私有的，只归属一个用户。
        // 这样在不实现 RBAC 的情况下，也能拥有真实权限边界。
        if (existsByOwnerAndName(currentUserId, name)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_ALREADY_EXISTS);
        }

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setOwnerUserId(currentUserId);
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(blankToNull(request.description()));
        knowledgeBase.setVisibility(VISIBILITY_PRIVATE);
        knowledgeBase.setStatus(STATUS_ACTIVE);
        knowledgeBase.setIsDeleted(NOT_DELETED);

        try {
            knowledgeBaseMapper.insert(knowledgeBase);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_ALREADY_EXISTS);
        }

        return KnowledgeBaseResponse.from(knowledgeBase);
    }

    @Override
    public PageResponse<KnowledgeBaseResponse> list(Long currentUserId, long page, long size) {
        long total = knowledgeBaseMapper.selectCount(baseOwnerQuery(currentUserId));
        long offset = (page - 1) * size;

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectList(baseOwnerQuery(currentUserId)
                .orderByDesc(KnowledgeBase::getUpdatedAt)
                .orderByDesc(KnowledgeBase::getId)
                .last("LIMIT " + size + " OFFSET " + offset));

        List<KnowledgeBaseResponse> records = knowledgeBases.stream()
                .map(KnowledgeBaseResponse::from)
                .toList();

        return PageResponse.of(records, page, size, total);
    }

    @Override
    public KnowledgeBaseResponse getDetail(Long currentUserId, Long knowledgeBaseId) {
        return KnowledgeBaseResponse.from(getOwnedKnowledgeBase(currentUserId, knowledgeBaseId));
    }

    @Override
    @Transactional
    public void delete(Long currentUserId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = getOwnedKnowledgeBase(currentUserId, knowledgeBaseId);
        if (hasActiveDocuments(currentUserId, knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Delete documents before deleting knowledge base");
        }

        // 唯一索引包含 is_deleted，但如果删除后仍保留原 name，
        // 多条已删除同名记录仍可能冲突。追加 id 可以释放原名称，允许后续重建。
        knowledgeBase.setName(deletedName(knowledgeBase));
        knowledgeBase.setStatus(STATUS_ARCHIVED);
        knowledgeBase.setIsDeleted(DELETED);
        knowledgeBaseMapper.updateById(knowledgeBase);
        runAfterCommit(() -> knowledgeBaseAccessService.evict(knowledgeBaseId));
    }

    private KnowledgeBase getOwnedKnowledgeBase(Long currentUserId, Long knowledgeBaseId) {
        return knowledgeBaseAccessService.requireOwned(currentUserId, knowledgeBaseId);
    }

    private boolean existsByOwnerAndName(Long ownerUserId, String name) {
        Long count = knowledgeBaseMapper.selectCount(baseOwnerQuery(ownerUserId)
                .eq(KnowledgeBase::getName, name));
        return count != null && count > 0;
    }

    private boolean hasActiveDocuments(Long ownerUserId, Long knowledgeBaseId) {
        Long count = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getOwnerUserId, ownerUserId)
                .eq(Document::getKbId, knowledgeBaseId)
                .eq(Document::getIsDeleted, NOT_DELETED));
        return count != null && count > 0;
    }

    private LambdaQueryWrapper<KnowledgeBase> baseOwnerQuery(Long ownerUserId) {
        // 集中封装 owner + 逻辑删除过滤，保证所有读写路径使用同一套约束。
        return new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOwnerUserId, ownerUserId)
                .eq(KnowledgeBase::getIsDeleted, NOT_DELETED);
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String deletedName(KnowledgeBase knowledgeBase) {
        String suffix = "#deleted#" + knowledgeBase.getId();
        String originalName = knowledgeBase.getName() == null ? "" : knowledgeBase.getName();
        int maxOriginalLength = Math.max(0, 128 - suffix.length());
        if (originalName.length() > maxOriginalLength) {
            originalName = originalName.substring(0, maxOriginalLength);
        }
        return originalName + suffix;
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
