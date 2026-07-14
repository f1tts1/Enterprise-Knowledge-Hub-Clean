package com.example.ekb.common.constants;

public final class DocumentIndexStatus {

    // 这些常量直接对应 MySQL document.index_status。
    // 多个模块都会读写该字段，所以集中定义，避免上传、索引、删除、检索之间出现状态名漂移。
    // 这里只放状态值，不放流转规则；流转规则仍留在各自业务 Service 中，避免演变成通用状态机。
    public static final String PENDING_INDEX = "PENDING_INDEX";
    public static final String INDEXING = "INDEXING";
    public static final String INDEXED = "INDEXED";
    public static final String INDEX_FAILED = "INDEX_FAILED";
    public static final String DELETING = "DELETING";
    public static final String DELETED = "DELETED";
    public static final String DELETE_FAILED = "DELETE_FAILED";

    private DocumentIndexStatus() {
    }
}
