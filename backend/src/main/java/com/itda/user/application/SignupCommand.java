package com.itda.user.application;

import com.itda.user.domain.UserRole;

/** 회원가입 유스케이스 입력 (presentation의 Request DTO와 분리) */
public record SignupCommand(String email, String password, String name, UserRole role) {}
