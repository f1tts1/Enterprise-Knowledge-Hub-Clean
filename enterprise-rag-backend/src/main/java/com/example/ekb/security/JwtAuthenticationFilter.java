package com.example.ekb.security;

import java.io.IOException;
import java.util.List;

import com.example.ekb.user.entity.User;
import com.example.ekb.user.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final AuthUserCache authUserCache;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserMapper userMapper,
            AuthUserCache authUserCache
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.authUserCache = authUserCache;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            authenticate(token);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void authenticate(String token) {
        try {
            if (!jwtTokenProvider.isValid(token)) {
                return;
            }

            Long userId = jwtTokenProvider.getUserId(token);
            CachedAuthUser authUser = loadAuthUser(userId);
            if (authUser == null || !authUser.isActive()) {
                return;
            }

            LoginUser loginUser = authUser.toLoginUser();
            // V1 暂不挂载 authorities，因为 RBAC 后置。
            // 当前登录用户 principal 已足够完成 owner 权限校验。
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException ex) {
            SecurityContextHolder.clearContext();
            log.warn("JWT authentication failed: {}", ex.getMessage());
        }
    }

    private CachedAuthUser loadAuthUser(Long userId) {
        CachedAuthUser cachedUser = authUserCache.get(userId).orElse(null);
        if (cachedUser != null) {
            return cachedUser;
        }

        User user = userMapper.selectById(userId);
        // JWT 只能证明 token 是我们签发的。用户状态仍以 MySQL 为准；
        // Redis 只缓存短 TTL 登录态快照，故障或未命中时回源 MySQL。
        if (user == null || Integer.valueOf(1).equals(user.getIsDeleted()) || !"ACTIVE".equals(user.getStatus())) {
            authUserCache.evict(userId);
            return null;
        }

        CachedAuthUser authUser = CachedAuthUser.from(user);
        authUserCache.put(authUser);
        return authUser;
    }
}
