package com.example.ekb.user.controller;

import com.example.ekb.common.response.ApiResponse;
import com.example.ekb.security.LoginUser;
import com.example.ekb.user.dto.UserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.success(new UserResponse(
                loginUser.id(),
                loginUser.username(),
                loginUser.nickname(),
                loginUser.email(),
                loginUser.status()
        ));
    }
}
