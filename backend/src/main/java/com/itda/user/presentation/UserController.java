package com.itda.user.presentation;

import com.itda.global.response.ApiResponse;
import com.itda.user.application.UserService;
import com.itda.user.presentation.dto.SignupRequest;
import com.itda.user.presentation.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    /** 1-1 회원가입 */
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(UserResponse.from(userService.signup(request.toCommand())));
    }

    /** 사용자 조회 (JWT 도입 전 임시 — 이후 /users/me 로 대체) */
    @GetMapping("/users/{userId}")
    public ApiResponse<UserResponse> getUser(@PathVariable Long userId) {
        return ApiResponse.ok(UserResponse.from(userService.getUser(userId)));
    }
}
