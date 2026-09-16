package com.itda.user.dto;

import com.itda.user.service.UserSummary;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 사용자 요약 응답 (API 명세 3.2·3.2.1절).
 *
 * <p>가입과 내 정보 조회가 같은 형태를 쓴다. 두 API가 각자 DTO를 가지면 한쪽만 고쳐져 응답이 갈라진다.
 *
 * <p>{@code userId}가 문자열인 이유는 계약이 BIGINT의 10진수 문자열로 못박기 때문이다. JSON number로 보내면 큰 값에서 정밀도를 잃는 클라이언트가
 * 있다.
 *
 * <p>{@code level}은 {@code null}일 수 있지만 <b>필드를 생략하지 않는다</b>. 값이 없다는 것과 필드가 없다는 것은 다르고, 프론트는 이 값으로
 * 자가 진단 필요 여부를 판단한다. record 직렬화는 기본적으로 null 필드를 포함하므로 별도 설정을 두지 않는다.
 *
 * <p>{@code toString()}을 가린다 — 아이디·이메일이 들어 있고, DTO는 로그·예외 message로 흘러가기 쉽다. 직렬화는 Jackson이 record
 * 컴포넌트를 직접 읽으므로 JSON 결과는 그대로다.
 *
 * <p>{@code createdAt}은 ISO 8601 UTC {@code Z} 표기다. 저장 값은 UTC {@code LocalDateTime}이라 시간대 정보가 없으므로
 * 여기서 UTC로 못박아 직렬화한다 — 서버 기본 시간대에 맡기면 배포 환경에 따라 값이 달라진다.
 */
public record UserSummaryResponse(
    String userId,
    String username,
    String email,
    String role,
    boolean emailVerified,
    String level,
    String createdAt) {

  private static final DateTimeFormatter UTC_ISO_8601 =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

  public static UserSummaryResponse from(UserSummary summary) {
    return new UserSummaryResponse(
        Long.toString(summary.userId()),
        summary.username(),
        summary.email(),
        summary.role().name(),
        summary.emailVerified(),
        summary.level(),
        toUtcIso8601(summary.createdAt()));
  }

  private static String toUtcIso8601(LocalDateTime createdAt) {
    return createdAt.atOffset(ZoneOffset.UTC).format(UTC_ISO_8601);
  }

  @Override
  public String toString() {
    return "UserSummaryResponse[REDACTED]";
  }
}
