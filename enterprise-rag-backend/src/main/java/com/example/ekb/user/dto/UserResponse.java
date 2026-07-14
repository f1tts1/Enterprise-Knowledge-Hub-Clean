package com.example.ekb.user.dto;

import com.example.ekb.user.entity.User;

public record UserResponse(
        Long id,
        String username,
        String nickname,
        String email,
        String status
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getStatus()
        );
    }
}
