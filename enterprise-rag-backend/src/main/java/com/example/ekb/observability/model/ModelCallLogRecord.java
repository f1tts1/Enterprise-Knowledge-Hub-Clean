package com.example.ekb.observability.model;

/**
 * 一次模型调用的可观测元数据。
 *
 * <p>该对象故意不提供 prompt、question、chunk、answer 或凭证字段，调用方只能记录
 * 定位故障和统计成本所需的最小元数据。errorMessage 也只能传入脱离业务正文的错误摘要。</p>
 */
public record ModelCallLogRecord(
        String requestId,
        Long userId,
        String provider,
        String model,
        String callType,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        Boolean success,
        String errorMessage
) {
}
