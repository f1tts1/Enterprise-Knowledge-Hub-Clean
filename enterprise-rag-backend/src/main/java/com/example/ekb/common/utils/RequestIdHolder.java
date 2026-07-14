package com.example.ekb.common.utils;

import org.slf4j.MDC;

public final class RequestIdHolder {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private RequestIdHolder() {
    }

    public static String getRequestId() {
        return MDC.get(REQUEST_ID_KEY);
    }
}
