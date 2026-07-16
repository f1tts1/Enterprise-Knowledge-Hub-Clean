package com.example.ekb.common.utils;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class RequestIdHolder {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final int MAX_REQUEST_ID_LENGTH = 64;

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final String INDEX_TASK_PREFIX = "index-task-";

    private RequestIdHolder() {
    }

    public static String getRequestId() {
        return MDC.get(REQUEST_ID_KEY);
    }

    public static String normalizeOrGenerate(String candidate) {
        String normalized = StringUtils.hasText(candidate) ? candidate.trim() : null;
        if (normalized != null && SAFE_REQUEST_ID.matcher(normalized).matches()) {
            return normalized;
        }
        return UUID.randomUUID().toString();
    }

    public static String forIndexingTask(Long indexingTaskId) {
        if (indexingTaskId == null || indexingTaskId <= 0) {
            return normalizeOrGenerate(null);
        }
        return INDEX_TASK_PREFIX + indexingTaskId;
    }

    /**
     * 为复用线程建立临时 MDC scope。返回旧值，调用方必须在 finally 中 restore。
     */
    public static String setRequestId(String requestId) {
        String previous = getRequestId();
        MDC.put(REQUEST_ID_KEY, normalizeOrGenerate(requestId));
        return previous;
    }

    public static void restoreRequestId(String previous) {
        if (StringUtils.hasText(previous)) {
            MDC.put(REQUEST_ID_KEY, previous);
        } else {
            MDC.remove(REQUEST_ID_KEY);
        }
    }
}
