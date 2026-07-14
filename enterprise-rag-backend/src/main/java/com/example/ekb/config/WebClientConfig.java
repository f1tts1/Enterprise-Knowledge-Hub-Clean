package com.example.ekb.config;

import java.time.Duration;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient aiServiceWebClient(
            WebClient.Builder builder,
            @Value("${app.ai-service.base-url}") String baseUrl,
            @Value("${app.ai-service.connect-timeout-ms:2000}") int connectTimeoutMillis,
            @Value("${app.ai-service.response-timeout-seconds:180}") long responseTimeoutSeconds
    ) {
        HttpClient httpClient = HttpClient.create()
                // 连接超时保持较短：Python 服务没有启动时要快速失败，
                // 让索引任务尽快进入 FAILED，便于前端展示和后续重试。
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                // 响应超时不能太短。本地 embedding 第一次加载模型时可能需要几十秒，
                // 如果沿用早期连通性验证阶段的 5 秒超时，会把正常索引误判为失败。
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));

        return builder
                // 所有 AI 服务客户端共享同一个 baseUrl。
                // 具体客户端只关心 endpoint 路径和 DTO 契约。
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
