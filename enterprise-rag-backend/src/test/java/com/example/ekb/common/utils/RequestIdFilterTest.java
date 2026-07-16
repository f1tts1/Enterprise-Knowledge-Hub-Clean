package com.example.ekb.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPreserveSafeRequestIdAndClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdHolder.REQUEST_ID_HEADER, "job-demo_2026:01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInsideChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                requestIdInsideChain.set(RequestIdHolder.getRequestId());

        filter.doFilter(request, response, chain);

        assertThat(requestIdInsideChain.get()).isEqualTo("job-demo_2026:01");
        assertThat(response.getHeader(RequestIdHolder.REQUEST_ID_HEADER))
                .isEqualTo("job-demo_2026:01");
        assertThat(RequestIdHolder.getRequestId()).isNull();
    }

    @Test
    void shouldReplaceUnsafeOrOversizedRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdHolder.REQUEST_ID_HEADER, "unsafe request id/" + "x".repeat(80));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                requestIdInsideChain.set(RequestIdHolder.getRequestId()));

        assertThat(requestIdInsideChain.get())
                .hasSize(36)
                .matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(RequestIdHolder.REQUEST_ID_HEADER))
                .isEqualTo(requestIdInsideChain.get());
        assertThat(RequestIdHolder.getRequestId()).isNull();
    }

    @Test
    void shouldRestorePreviousMdcValueForReusableThreadScope() {
        MDC.put(RequestIdHolder.REQUEST_ID_KEY, "outer-request");

        String previous = RequestIdHolder.setRequestId(RequestIdHolder.forIndexingTask(42L));
        assertThat(RequestIdHolder.getRequestId()).isEqualTo("index-task-42");

        RequestIdHolder.restoreRequestId(previous);
        assertThat(RequestIdHolder.getRequestId()).isEqualTo("outer-request");
    }
}
