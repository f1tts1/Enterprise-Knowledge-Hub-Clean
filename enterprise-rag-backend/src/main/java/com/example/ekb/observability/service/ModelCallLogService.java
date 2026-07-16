package com.example.ekb.observability.service;

import com.example.ekb.observability.model.ModelCallLogRecord;

public interface ModelCallLogService {

    /**
     * 尽力记录一次模型调用。可观测数据不可用时不会影响原业务调用。
     */
    void record(ModelCallLogRecord record);
}
