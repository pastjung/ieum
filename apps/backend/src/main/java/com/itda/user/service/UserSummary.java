package com.itda.user.service;

import com.itda.problem.domain.Level;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;

/**
 * 가입·내 정보 조회가 공유하는 사용자 요약 (API 명세 3.2·3.2.1절).
 *
 * <p>엔티티를 presentation까지 들고 가지 않으려고 application 경계에서 값을 복사한다. 엔티티가 그대로 올라가면 lazy 로딩·detached 상태·JPA
 * 매핑이 응답 직렬화에 섞이고, 응답에 넣지 않기로 한 {@code password_hash}·{@code auth_version}·{@code row_version}까지 늘
 * 곁에 있게 된다.
 *
 * <p>{@code emailVerified}는 저장 값이 아니라 {@code email_verified_at != null}의 파생값이다. 인증 시각 자체는 응답 계약에
 * 없다.
 *
 * <p>{@code toString()}을 가린다 — username·email은 개인정보이고 이 객체는 로그·예외에 함께 찍히기 쉽다.
 */
public record UserSummary(
    long userId,
    String username,
    String email,
    UserRole role,
    boolean emailVerified,
    Level level,
    LocalDateTime createdAt) {

  public static UserSummary from(User user) {
    return new UserSummary(
        user.getId(),
        user.getUsername(),
        user.getEmail().getValue(),
        user.getRole(),
        user.isEmailVerified(),
        user.getLevel(),
        user.getCreatedAt());
  }

  @Override
  public String toString() {
    return "UserSummary[REDACTED]";
  }
}
