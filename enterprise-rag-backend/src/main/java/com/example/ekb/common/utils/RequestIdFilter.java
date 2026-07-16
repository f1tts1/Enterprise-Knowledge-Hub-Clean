package com.example.ekb.common.utils;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = RequestIdHolder.normalizeOrGenerate(
                request.getHeader(RequestIdHolder.REQUEST_ID_HEADER)
        );

        // 同一个 requestId 会写入 MDC、响应头和 ApiResponse。
        // 这样前端截图里的 requestId 可以反查后端日志。
        MDC.put(RequestIdHolder.REQUEST_ID_KEY, requestId);
        response.setHeader(RequestIdHolder.REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIdHolder.REQUEST_ID_KEY);
        }
    }
}
