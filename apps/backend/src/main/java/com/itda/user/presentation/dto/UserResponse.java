package com.itda.user.presentation.dto;

import com.itda.user.application.UserInfo;
import java.time.LocalDateTime;

public record UserResponse(Long userId, String email, String name, String role, LocalDateTime createdAt) {

    public static UserResponse from(UserInfo info) {
        return new UserResponse(info.userId(), info.email(), info.name(), info.role(), info.createdAt());
    }
}
