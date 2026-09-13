package com.itda.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.user.application.UserSummary;
import com.itda.user.domain.UserRole;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 응답 DTO의 변환과 노출 범위를 고정한다 (API 명세 3.2·3.7절).
 *
 * <p>여기서 확인하는 것은 두 가지다 — 계약이 정한 필드가 그대로 직렬화되는지, 그리고 그 값이 <b>로그로는</b> 새지 않는지.
 */
class UserSummaryResponseTest {

  private static final long USER_ID = 42L;
  private static final String USERNAME = "learner01";
  private static final String EMAIL = "learner01@example.com";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 13, 9, 0);

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static UserSummaryResponse response(String level) {
    return UserSummaryResponse.from(
        new UserSummary(USER_ID, USERNAME, EMAIL, UserRole.LEARNER, true, level, CREATED_AT));
  }

  @Test
  void 계약이_정한_일곱_필드를_그대로_직렬화한다() {
    Map<String, Object> json =
        MAPPER.readValue(MAPPER.writeValueAsString(response("L3")), Map.class);

    assertThat(json)
        .containsOnlyKeys(
            "userId", "username", "email", "role", "emailVerified", "level", "createdAt");
    assertThat(json)
        .containsEntry("userId", "42")
        .containsEntry("username", USERNAME)
        .containsEntry("email", EMAIL)
        .containsEntry("role", "LEARNER")
        .containsEntry("emailVerified", true)
        .containsEntry("level", "L3")
        .containsEntry("createdAt", "2026-09-13T09:00:00.000Z");
  }

  @Test
  void level이_null이어도_필드를_생략하지_않는다() {
    Map<String, Object> json =
        MAPPER.readValue(MAPPER.writeValueAsString(response(null)), Map.class);

    assertThat(json).containsKey("level");
    assertThat(json.get("level")).isNull();
  }

  @Test
  void toString에_개인정보가_없다() {
    // DTO는 로그·예외 message로 흘러가기 쉽다. record 기본 toString은 모든 컴포넌트를 그대로 찍는다.
    UserSummaryResponse response = response("L3");

    assertThat(response).hasToString("UserSummaryResponse[REDACTED]");
    assertThat(response.toString())
        .doesNotContain(USERNAME)
        .doesNotContain(EMAIL)
        .doesNotContain(Long.toString(USER_ID));
  }

  @Test
  void toString을_가려도_직렬화_결과는_그대로다() {
    // 마스킹을 @JsonIgnore나 필드 제거로 구현하면 여기서 깨진다.
    assertThat(MAPPER.writeValueAsString(response("L3"))).contains(USERNAME).contains(EMAIL);
  }
}
