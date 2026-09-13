package com.itda.user.application.port.out;

import java.util.Objects;

/**
 * 방금 발급한 Refresh Token과 Redis 등록에 필요한 값.
 *
 * <p>compact 문자열만 돌려주지 않는 이유: 호출자가 {@code jti}·{@code familyId}·시각을 알아야 Redis 상태를 등록하고 회전할 수 있는데,
 * 그러려고 방금 만든 토큰을 다시 파싱하면 발급과 저장이 서로 다른 해석 경로를 갖게 된다. 발급기가 정한 값을 그대로 넘긴다.
 *
 * <p>record 기본 구현을 쓰지 않는 이유는 {@code compactToken} 때문이다. 기본 {@code toString()}·{@code equals()}는 토큰
 * 원문을 그대로 드러내고, 특히 테스트 실패 message와 디버거에서 새기 쉽다. 값 비교는 {@code jti}로 충분하다.
 */
public final class IssuedRefreshToken {

  private static final String REDACTED = "IssuedRefreshToken[REDACTED]";

  private final String compactToken;
  private final String jti;
  private final String familyId;
  private final long issuedAtEpochSecond;
  private final long expiresAtEpochSecond;

  public IssuedRefreshToken(
      String compactToken,
      String jti,
      String familyId,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond) {
    this.compactToken = requireText(compactToken, "compact 토큰");
    // 식별자 규칙은 RefreshTokenRecord 한 곳에만 둔다 — 발급 결과가 저장 계약보다 느슨하면
    // 발급은 됐는데 저장할 수 없는 값이 만들어진다.
    this.jti = RefreshTokenRecord.requireJti(jti);
    this.familyId = RefreshTokenRecord.requireFamilyId(familyId);
    if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
      throw new IllegalArgumentException("발급·만료 시각이 올바르지 않습니다.");
    }
    this.issuedAtEpochSecond = issuedAtEpochSecond;
    this.expiresAtEpochSecond = expiresAtEpochSecond;
  }

  /** 클라이언트에게 전달할 때만 호출한다. 저장·로그 대상이 아니다. */
  public String compactToken() {
    return this.compactToken;
  }

  public String jti() {
    return this.jti;
  }

  public String familyId() {
    return this.familyId;
  }

  public long issuedAtEpochSecond() {
    return this.issuedAtEpochSecond;
  }

  public long expiresAtEpochSecond() {
    return this.expiresAtEpochSecond;
  }

  /** 토큰 원문을 비교 대상에서 제외한다. 같은 발급인지는 {@code jti}가 결정한다. */
  @Override
  public boolean equals(Object other) {
    return other instanceof IssuedRefreshToken issued && this.jti.equals(issued.jti);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.jti);
  }

  @Override
  public String toString() {
    return REDACTED;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "이(가) 없습니다.");
    }
    return value;
  }
}
