package com.example.ekb.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.ekb.common.constants.DocumentIndexStatus;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.document.entity.Document;
import com.example.ekb.document.mapper.DocumentMapper;
import com.example.ekb.knowledge.access.KnowledgeBaseAccessService;
import com.example.ekb.knowledge.entity.KnowledgeBase;
import com.example.ekb.knowledge.mapper.KnowledgeBaseMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

class KnowledgeBaseServiceImplTest {

    private static final long OWNER_USER_ID = 11L;
    private static final long KNOWLEDGE_BASE_ID = 22L;

    private KnowledgeBaseMapper knowledgeBaseMapper;

    private DocumentMapper documentMapper;

    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test");
        assistant.setCurrentNamespace(DocumentMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, Document.class);
    }

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = subclassMock(KnowledgeBaseMapper.class);
        documentMapper = subclassMock(DocumentMapper.class);
        knowledgeBaseAccessService = subclassMock(KnowledgeBaseAccessService.class);
        knowledgeBaseService = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                documentMapper,
                knowledgeBaseAccessService
        );
    }

    @Test
    void deleteRejectsWhenAnyNonDeletedDocumentStillNeedsCleanup() {
        KnowledgeBase knowledgeBase = activeKnowledgeBase();
        when(knowledgeBaseAccessService.requireOwnedForWrite(OWNER_USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase);
        // 该计数代表任一状态不为 DELETED 的子文档，DELETE_FAILED 也必须阻止删除知识库。
        when(documentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> knowledgeBaseService.delete(OWNER_USER_ID, KNOWLEDGE_BASE_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage())
                            .isEqualTo("Delete all documents and finish failed cleanup before deleting knowledge base");
                });

        verify(knowledgeBaseAccessService).requireOwnedForWrite(OWNER_USER_ID, KNOWLEDGE_BASE_ID);
        verify(knowledgeBaseMapper, never()).updateById(any(KnowledgeBase.class));
        verify(knowledgeBaseAccessService, never()).evict(KNOWLEDGE_BASE_ID);

        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper).selectCount(queryCaptor.capture());
        assertThat(queryCaptor.getValue()).isInstanceOf(LambdaQueryWrapper.class);
        LambdaQueryWrapper<?> query = (LambdaQueryWrapper<?>) queryCaptor.getValue();
        assertThat(query.getSqlSegment()).contains("owner_user_id", "kb_id", "index_status");
        assertThat(query.getParamNameValuePairs())
                .containsValue(OWNER_USER_ID)
                .containsValue(KNOWLEDGE_BASE_ID)
                .containsValue(DocumentIndexStatus.DELETED);
    }

    @Test
    void deleteLogicallyDeletesKnowledgeBaseWhenAllDocumentsAreDeleted() {
        KnowledgeBase knowledgeBase = activeKnowledgeBase();
        when(knowledgeBaseAccessService.requireOwnedForWrite(OWNER_USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase);
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeBaseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);

        knowledgeBaseService.delete(OWNER_USER_ID, KNOWLEDGE_BASE_ID);

        verify(knowledgeBaseAccessService).requireOwnedForWrite(OWNER_USER_ID, KNOWLEDGE_BASE_ID);
        ArgumentCaptor<KnowledgeBase> knowledgeBaseCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(knowledgeBaseCaptor.capture());
        KnowledgeBase deletedKnowledgeBase = knowledgeBaseCaptor.getValue();
        assertThat(deletedKnowledgeBase.getId()).isEqualTo(KNOWLEDGE_BASE_ID);
        assertThat(deletedKnowledgeBase.getName()).isEqualTo("team-handbook#deleted#22");
        assertThat(deletedKnowledgeBase.getStatus()).isEqualTo("ARCHIVED");
        assertThat(deletedKnowledgeBase.getIsDeleted()).isEqualTo(1);
        verify(knowledgeBaseAccessService).evict(KNOWLEDGE_BASE_ID);
    }

    private KnowledgeBase activeKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(KNOWLEDGE_BASE_ID);
        knowledgeBase.setOwnerUserId(OWNER_USER_ID);
        knowledgeBase.setName("team-handbook");
        knowledgeBase.setStatus("ACTIVE");
        knowledgeBase.setIsDeleted(0);
        return knowledgeBase;
    }

    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.SUBCLASS));
    }
}
