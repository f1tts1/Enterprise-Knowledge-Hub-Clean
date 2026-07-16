package com.example.ekb.observability.service.impl;

import java.util.Locale;
import java.util.regex.Pattern;

import com.example.ekb.observability.entity.ModelCallLog;
import com.example.ekb.observability.mapper.ModelCallLogMapper;
import com.example.ekb.observability.model.ModelCallLogRecord;
import com.example.ekb.observability.service.ModelCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ModelCallLogServiceImpl implements ModelCallLogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLogServiceImpl.class);

    private static final String UNKNOWN = "UNKNOWN";
    private static final String SENSITIVE_MODEL_CONTENT_REDACTED = "SENSITIVE_MODEL_CONTENT_REDACTED";
    private static final int REQUEST_ID_MAX_LENGTH = 64;
    private static final int PROVIDER_MAX_LENGTH = 64;
    private static final int MODEL_MAX_LENGTH = 128;
    private static final int CALL_TYPE_MAX_LENGTH = 32;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 2048;

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SENSITIVE_MODEL_CONTENT_PATTERN = Pattern.compile(
            "(?i)\\b(?:prompt|question|chunk|answer)\\b[\"']?\\s*[:=]"
    );
    private static final Pattern SENSITIVE_KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)(\\b[\"']?(?:api[-_ ]?key|key|access[-_ ]?token|refresh[-_ ]?token|secret|"
                    + "password|credential)[\"']?\\s*[:=]\\s*)"
                    + "(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^\\s,;}]+)"
    );
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)(\\b[\"']?authorization[\"']?\\s*[:=]\\s*)(?:Bearer\\s+)?[^\\s,;}]+"
    );
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;}]+");

    private final ModelCallLogMapper modelCallLogMapper;

    public ModelCallLogServiceImpl(ModelCallLogMapper modelCallLogMapper) {
        this.modelCallLogMapper = modelCallLogMapper;
    }

    @Override
    public void record(ModelCallLogRecord record) {
        if (record == null) {
            log.warn("跳过空的模型调用日志记录");
            return;
        }

        ModelCallLog callLog = null;
        try {
            callLog = normalize(record);
            int insertedRows = modelCallLogMapper.insert(callLog);
            if (insertedRows != 1) {
                log.warn(
                        "模型调用日志写入行数异常: requestId={}, callType={}, insertedRows={}",
                        callLog.getRequestId(),
                        callLog.getCallType(),
                        insertedRows
                );
            }
        } catch (Exception ex) {
            // 可观测旁路不能反向影响问答或索引主链路，也不打印可能携带响应正文的异常 message。
            log.warn(
                    "模型调用日志写入失败: requestId={}, callType={}, exceptionType={}",
                    callLog == null ? UNKNOWN : callLog.getRequestId(),
                    callLog == null ? UNKNOWN : callLog.getCallType(),
                    ex.getClass().getSimpleName()
            );
        }
    }

    private ModelCallLog normalize(ModelCallLogRecord record) {
        ModelCallLog callLog = new ModelCallLog();
        callLog.setRequestId(normalizeRequiredText(record.requestId(), REQUEST_ID_MAX_LENGTH));
        callLog.setUserId(normalizeUserId(record.userId()));
        callLog.setProvider(normalizeRequiredText(record.provider(), PROVIDER_MAX_LENGTH));
        callLog.setModel(normalizeRequiredText(record.model(), MODEL_MAX_LENGTH));
        callLog.setCallType(normalizeCallType(record.callType()));
        callLog.setPromptTokens(nonNegative(record.promptTokens()));
        callLog.setCompletionTokens(nonNegative(record.completionTokens()));
        callLog.setLatencyMs(nonNegative(record.latencyMs()));
        callLog.setSuccess(Boolean.TRUE.equals(record.success()));
        callLog.setErrorMessage(callLog.getSuccess() ? null : normalizeErrorMessage(record.errorMessage()));
        return callLog;
    }

    private String normalizeCallType(String callType) {
        String normalized = StringUtils.hasText(callType)
                ? WHITESPACE_PATTERN.matcher(callType.strip()).replaceAll(" ")
                : UNKNOWN;
        return truncate(normalized.toUpperCase(Locale.ROOT), CALL_TYPE_MAX_LENGTH);
    }

    private String normalizeRequiredText(String value, int maxLength) {
        String normalized = StringUtils.hasText(value)
                ? WHITESPACE_PATTERN.matcher(value.strip()).replaceAll(" ")
                : UNKNOWN;
        return truncate(normalized, maxLength);
    }

    private Long normalizeUserId(Long userId) {
        return userId != null && userId > 0 ? userId : null;
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    private String normalizeErrorMessage(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return null;
        }
        if (SENSITIVE_MODEL_CONTENT_PATTERN.matcher(errorMessage).find()) {
            // 一旦错误文本看起来携带模型输入或输出，宁可牺牲错误细节，也不把业务正文写入数据库。
            return SENSITIVE_MODEL_CONTENT_REDACTED;
        }
        String redacted = AUTHORIZATION_PATTERN.matcher(errorMessage.strip())
                .replaceAll("$1[REDACTED]");
        redacted = SENSITIVE_KEY_VALUE_PATTERN.matcher(redacted)
                .replaceAll("$1[REDACTED]");
        redacted = BEARER_TOKEN_PATTERN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        return truncate(redacted, ERROR_MESSAGE_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
