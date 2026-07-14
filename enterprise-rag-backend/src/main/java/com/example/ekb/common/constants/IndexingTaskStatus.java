package com.example.ekb.common.constants;

public final class IndexingTaskStatus {

    // 这些常量直接对应 MySQL indexing_task.status。
    // task 状态会被文档上传、Redis 消费、人工重试和超时标记共同使用，集中定义可以减少硬编码分叉。
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private IndexingTaskStatus() {
    }
}
