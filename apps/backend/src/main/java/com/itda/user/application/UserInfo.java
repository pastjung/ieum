package com.itda.user.application;

import com.itda.user.domain.User;
import java.time.LocalDateTime;

/** 유스케이스 출력 모델 (엔티티를 presentation 계층에 직접 노출하지 않는다) */
public record UserInfo(
    Long userId, String email, String name, String role, LocalDateTime createdAt) {

  public static UserInfo from(User user) {
    return new UserInfo(
        user.getId(),
        user.getEmail().getValue(),
        user.getName(),
        user.getRole().name(),
        user.getCreatedAt());
  }
}
