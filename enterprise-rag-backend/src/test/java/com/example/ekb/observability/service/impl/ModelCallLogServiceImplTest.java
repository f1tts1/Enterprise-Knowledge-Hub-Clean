package com.example.ekb.observability.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.example.ekb.observability.entity.ModelCallLog;
import com.example.ekb.observability.mapper.ModelCallLogMapper;
import com.example.ekb.observability.model.ModelCallLogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

class ModelCallLogServiceImplTest {

    private ModelCallLogMapper modelCallLogMapper;

    private ModelCallLogServiceImpl modelCallLogService;

    @BeforeEach
    void setUp() {
        modelCallLogMapper = mock(
                ModelCallLogMapper.class,
                withSettings().mockMaker(MockMakers.SUBCLASS)
        );
        modelCallLogService = new ModelCallLogServiceImpl(modelCallLogMapper);
    }

    @Test
    void recordMapsSuccessfulCallMetadata() {
        when(modelCallLogMapper.insert(any(ModelCallLog.class))).thenReturn(1);
        ModelCallLogRecord record = new ModelCallLogRecord(
                "request-123",
                42L,
                "openai-compatible",
                "deepseek-chat",
                "chat",
                120,
                36,
                845L,
                true,
                "成功调用不应保留错误字段"
        );

        modelCallLogService.record(record);

        ModelCallLog inserted = captureInsertedLog();
        assertThat(inserted.getRequestId()).isEqualTo("request-123");
        assertThat(inserted.getUserId()).isEqualTo(42L);
        assertThat(inserted.getProvider()).isEqualTo("openai-compatible");
        assertThat(inserted.getModel()).isEqualTo("deepseek-chat");
        assertThat(inserted.getCallType()).isEqualTo("CHAT");
        assertThat(inserted.getPromptTokens()).isEqualTo(120);
        assertThat(inserted.getCompletionTokens()).isEqualTo(36);
        assertThat(inserted.getLatencyMs()).isEqualTo(845L);
        assertThat(inserted.getSuccess()).isTrue();
        assertThat(inserted.getErrorMessage()).isNull();
        assertThat(inserted.getCreatedAt()).isNull();
    }

    @Test
    void recordNormalizesDefaultsBoundsAndSensitiveErrorContent() {
        when(modelCallLogMapper.insert(any(ModelCallLog.class))).thenReturn(1);
        String longRequestId = "r".repeat(80);
        String longError = "api_key=secret-value Authorization:Bearer token-value "
                + "network failure " + "x".repeat(2200);
        ModelCallLogRecord record = new ModelCallLogRecord(
                longRequestId,
                -1L,
                "  ",
                null,
                "  embedding  ",
                -10,
                null,
                -25L,
                null,
                longError
        );

        modelCallLogService.record(record);

        ModelCallLog inserted = captureInsertedLog();
        assertThat(inserted.getRequestId()).hasSize(64).isEqualTo("r".repeat(64));
        assertThat(inserted.getUserId()).isNull();
        assertThat(inserted.getProvider()).isEqualTo("UNKNOWN");
        assertThat(inserted.getModel()).isEqualTo("UNKNOWN");
        assertThat(inserted.getCallType()).isEqualTo("EMBEDDING");
        assertThat(inserted.getPromptTokens()).isZero();
        assertThat(inserted.getCompletionTokens()).isZero();
        assertThat(inserted.getLatencyMs()).isZero();
        assertThat(inserted.getSuccess()).isFalse();
        assertThat(inserted.getErrorMessage())
                .hasSize(2048)
                .contains("api_key=[REDACTED]")
                .contains("Authorization:[REDACTED]")
                .doesNotContain("secret-value", "token-value");
    }

    @Test
    void recordDropsErrorDetailsWhenTheyContainModelInputOrOutputLabels() {
        when(modelCallLogMapper.insert(any(ModelCallLog.class))).thenReturn(1);
        ModelCallLogRecord record = new ModelCallLogRecord(
                "request-sensitive",
                42L,
                "provider",
                "model",
                "CHAT",
                0,
                0,
                10L,
                false,
                "prompt=private-content upstream failed"
        );

        modelCallLogService.record(record);

        assertThat(captureInsertedLog().getErrorMessage())
                .isEqualTo("SENSITIVE_MODEL_CONTENT_REDACTED")
                .doesNotContain("private-content");
    }

    @Test
    void recordUsesUnknownAndDatabaseColumnBoundsForMissingOrLongDimensions() {
        when(modelCallLogMapper.insert(any(ModelCallLog.class))).thenReturn(1);
        ModelCallLogRecord record = new ModelCallLogRecord(
                null,
                null,
                "p".repeat(80),
                "m".repeat(150),
                "c".repeat(40),
                null,
                null,
                null,
                false,
                "  "
        );

        modelCallLogService.record(record);

        ModelCallLog inserted = captureInsertedLog();
        assertThat(inserted.getRequestId()).isEqualTo("UNKNOWN");
        assertThat(inserted.getProvider()).isEqualTo("p".repeat(64));
        assertThat(inserted.getModel()).isEqualTo("m".repeat(128));
        assertThat(inserted.getCallType()).isEqualTo("C".repeat(32));
        assertThat(inserted.getErrorMessage()).isNull();
    }

    @Test
    void recordSwallowsMapperFailure() {
        when(modelCallLogMapper.insert(any(ModelCallLog.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        ModelCallLogRecord record = new ModelCallLogRecord(
                "request-456",
                null,
                "provider",
                "model",
                "CHAT",
                0,
                0,
                12L,
                false,
                "upstream timeout"
        );

        assertThatCode(() -> modelCallLogService.record(record)).doesNotThrowAnyException();

        verify(modelCallLogMapper).insert(any(ModelCallLog.class));
    }

    private ModelCallLog captureInsertedLog() {
        ArgumentCaptor<ModelCallLog> captor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(modelCallLogMapper).insert(captor.capture());
        return captor.getValue();
    }
}
