package com.example.ekb.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.security.AuthUserCacheProperties;
import com.example.ekb.security.JwtAuthenticationFilter;
import com.example.ekb.security.JwtProperties;
import com.example.ekb.security.RefreshTokenProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, AuthUserCacheProperties.class, RefreshTokenProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                // 后端是无状态 API 服务。V1 管理台接口不使用浏览器 session、
                // 表单登录和 CSRF token。
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                writeErrorResponse(
                                        response,
                                        objectMapper,
                                        HttpStatus.UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED.getDefaultMessage()
                                ))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeErrorResponse(
                                        response,
                                        objectMapper,
                                        HttpStatus.FORBIDDEN,
                                        ErrorCode.FORBIDDEN,
                                        ErrorCode.FORBIDDEN.getDefaultMessage()
                                ))
                )
                .authorizeHttpRequests(auth -> auth
                        // V1 删除了早期连通性端点后，匿名业务入口只保留登录和注册。
                        // /console/** 只是同源静态演示页，不放行业务 API。
                        // 其它业务接口都必须先经过 JWT 过滤器，避免后续新增接口时误放行。
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                // refresh/logout 依赖 refresh token 自身完成校验。
                                // 如果强制要求 access token，access 过期后反而无法撤销 refresh token。
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/console",
                                "/console/**",
                                "/favicon.ico"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // JWT 过滤器放在用户名密码过滤器之前，这样 Controller 可以通过
                // @AuthenticationPrincipal 拿到当前登录用户。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // V1 只配置 BCrypt 作为密码编码器，先保证凭证处理真实可靠，
        // 暂不引入复杂账户策略。
        return new BCryptPasswordEncoder();
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus httpStatus,
            ErrorCode errorCode,
            String message
    ) throws IOException {
        response.setStatus(httpStatus.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode, message));
    }
}
