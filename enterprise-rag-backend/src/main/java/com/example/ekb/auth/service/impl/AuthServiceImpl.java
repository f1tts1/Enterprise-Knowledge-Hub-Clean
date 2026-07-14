package com.example.ekb.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ekb.auth.dto.LoginRequest;
import com.example.ekb.auth.dto.LoginResponse;
import com.example.ekb.auth.dto.LogoutRequest;
import com.example.ekb.auth.dto.RefreshTokenRequest;
import com.example.ekb.auth.dto.RegisterRequest;
import com.example.ekb.auth.service.AuthService;
import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.exception.BusinessException;
import com.example.ekb.security.AuthUserCache;
import com.example.ekb.security.CachedAuthUser;
import com.example.ekb.security.IssuedRefreshToken;
import com.example.ekb.security.JwtProperties;
import com.example.ekb.security.JwtTokenProvider;
import com.example.ekb.security.RefreshTokenService;
import com.example.ekb.security.RefreshTokenSession;
import com.example.ekb.user.dto.UserResponse;
import com.example.ekb.user.entity.User;
import com.example.ekb.user.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String USER_STATUS_ACTIVE = "ACTIVE";
    private static final int NOT_DELETED = 0;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final AuthUserCache authUserCache;
    private final RefreshTokenService refreshTokenService;

    public AuthServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            AuthUserCache authUserCache,
            RefreshTokenService refreshTokenService
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.authUserCache = authUserCache;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = request.username().trim();
        // V1 注册逻辑刻意保持简单：username 是唯一登录标识。
        // 角色/RBAC 表等到核心 RAG 链路稳定后再补。
        if (findActiveUserByUsername(username) != null) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(username);
        // 不保存明文密码。BCrypt 自带盐值，在加入更完整的账户策略之前，
        // 对这个本地工程化项目已经足够。
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(defaultIfBlank(request.nickname(), username));
        user.setEmail(blankToNull(request.email()));
        user.setStatus(USER_STATUS_ACTIVE);
        user.setIsDeleted(NOT_DELETED);

        userMapper.insert(user);
        return UserResponse.from(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = findActiveUserByUsername(request.username().trim());
        // 用户不存在和密码错误都返回同一个错误，避免接口泄露哪些用户名存在。
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_USERNAME_OR_PASSWORD);
        }

        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        return issueLoginResponse(user);
    }

    @Override
    public LoginResponse refresh(RefreshTokenRequest request) {
        // consume 会先从 Redis 读取 refresh token 会话，再删除旧会话。
        // 这样刷新接口天然具备轮换语义：旧 refresh token 使用一次后立即失效。
        RefreshTokenSession session = refreshTokenService.consume(request.refreshToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));

        // Refresh token 只能证明“这个会话还存在”。用户是否仍然有效必须回源 MySQL，
        // 避免被删除、禁用的账号继续依靠旧 refresh token 换发新 access token。
        User user = userMapper.selectById(session.userId());
        if (user == null || Integer.valueOf(1).equals(user.getIsDeleted())) {
            authUserCache.evict(session.userId());
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            authUserCache.evict(session.userId());
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        return issueLoginResponse(user);
    }

    @Override
    public void logout(LogoutRequest request) {
        // 退出登录撤销的是 refresh token 会话。当前不撤销已签发 access token，
        // 由 30 分钟 TTL 自然过期，避免把每个 access token 都变成 Redis 状态。
        refreshTokenService.revoke(request.refreshToken());
    }

    private User findActiveUserByUsername(String username) {
        // 这里检查 is_deleted，因为 V1 表设计使用逻辑删除，
        // 后续可能保留审计或历史记录。
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getIsDeleted, NOT_DELETED)
                .last("LIMIT 1"));
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private LoginResponse issueLoginResponse(User user) {
        // access token 仍然是无状态 JWT；refresh token 则是 Redis 有状态会话。
        // 两者职责分离：前者用于访问业务 API，后者用于延长登录态和退出撤销。
        String accessToken = jwtTokenProvider.createToken(user);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        // 登录/刷新后预热认证用户缓存，减少接下来业务请求在 JWT 过滤器中的 user 表读取。
        authUserCache.put(CachedAuthUser.from(user));
        return new LoginResponse(
                accessToken,
                refreshToken.token(),
                "Bearer",
                jwtProperties.getExpiresInSeconds(),
                refreshToken.expiresInSeconds(),
                UserResponse.from(user)
        );
    }
}
