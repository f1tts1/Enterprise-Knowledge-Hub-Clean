package com.example.ekb.security;

import com.example.ekb.user.entity.User;

public record LoginUser(
        Long id,
        String username,
        String nickname,
        String email,
        String status
) {

    public static LoginUser from(User user) {
        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getStatus()
        );
    }
}
