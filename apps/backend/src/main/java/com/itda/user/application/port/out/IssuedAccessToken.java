package com.itda.user.application.port.out;

/**
 * Access Token 발급 결과 (API 명세 3.3.1절).
 *
 * <p>compact 문자열만 돌려주면 응답의 {@code accessTokenExpiresAt}을 만들 수 없다. 호출자가 "발급 시각 + TTL"을 스스로 계산하면 발급기의
 * clock·TTL과 미세하게 어긋난 값이 나가고, 발급된 JWT를 다시 파싱해 복구하면 서명 검증 없이 payload를 읽는 경로가 생긴다. 그래서 발급기가 실제로 쓴 값을
 * 그대로 실어 보낸다.
 *
 * <p>{@code toString()}을 가린다 — compact 원문이 들어 있다. 원문은 응답으로 나가는 경로에만 존재해야 한다.
 */
public record IssuedAccessToken(
    String compactToken, long issuedAtEpochSecond, long expiresAtEpochSecond) {

  public IssuedAccessToken {
    if (compactToken == null || compactToken.isBlank()) {
      throw new IllegalArgumentException("compact 토큰이 없습니다.");
    }
    if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
      throw new IllegalArgumentException("발급·만료 시각이 올바르지 않습니다.");
    }
  }

  @Override
  public String toString() {
    return "IssuedAccessToken[REDACTED]";
  }
}
