package com.example.ekb.security;

import com.example.ekb.user.entity.User;

public record CachedAuthUser(
        Long id,
        String username,
        String nickname,
        String email,
        String status,
        Integer isDeleted
) {

    private static final String USER_STATUS_ACTIVE = "ACTIVE";
    private static final int DELETED = 1;

    public static CachedAuthUser from(User user) {
        return new CachedAuthUser(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getStatus(),
                user.getIsDeleted()
        );
    }

    public boolean isActive() {
        return id != null
                && !Integer.valueOf(DELETED).equals(isDeleted)
                && USER_STATUS_ACTIVE.equals(status);
    }

    public LoginUser toLoginUser() {
        return new LoginUser(id, username, nickname, email, status);
    }
}
