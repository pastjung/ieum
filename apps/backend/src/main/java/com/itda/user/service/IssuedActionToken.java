package com.itda.user.service;

import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.repository.ActionTokenStore;
import java.util.Objects;

/**
 * 방금 발급한 계정 작업 토큰과 Redis 등록에 필요한 값.
 *
 * <p>compact 문자열만 돌려주지 않는 이유는 Refresh와 같다. 호출자가 {@code jti}·용도·시각을 알아야 Redis 상태를 등록할 수 있는데, 방금 만든
 * 토큰을 다시 파싱하면 발급과 저장이 서로 다른 해석 경로를 갖게 된다.
 *
 * <p>record를 쓰지 않는 이유도 같다. 기본 {@code toString()}·{@code equals()}가 토큰 원문을 그대로 드러내며, 특히 테스트 실패
 * message와 디버거에서 새기 쉽다. 값 비교는 {@code jti}와 용도로 충분하다.
 */
public final class IssuedActionToken {

  private static final String REDACTED = "IssuedActionToken[REDACTED]";

  private final String compactToken;
  private final String tokenHash;
  private final String jti;
  private final AccountActionPurpose purpose;
  private final long issuedAtEpochSecond;
  private final long expiresAtEpochSecond;

  public IssuedActionToken(
      String compactToken,
      String tokenHash,
      String jti,
      AccountActionPurpose purpose,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond) {
    if (compactToken == null || compactToken.isBlank()) {
      throw new IllegalArgumentException("compact 토큰이 없습니다.");
    }
    this.compactToken = compactToken;
    this.tokenHash = ActionTokenStore.TokenRecord.requireTokenHash(tokenHash);
    this.jti = ActionTokenStore.TokenRecord.requireJti(jti);
    this.purpose = ActionTokenStore.TokenRecord.requirePurpose(purpose);
    if (issuedAtEpochSecond <= 0 || expiresAtEpochSecond <= issuedAtEpochSecond) {
      throw new IllegalArgumentException("발급·만료 시각이 올바르지 않습니다.");
    }
    this.issuedAtEpochSecond = issuedAtEpochSecond;
    this.expiresAtEpochSecond = expiresAtEpochSecond;
  }

  /** 메일 본문에 실을 때만 호출한다. 저장·로그 대상이 아니다. */
  public String compactToken() {
    return this.compactToken;
  }

  /**
   * Redis에 저장할 해시. 발급기가 계산해 넘긴다.
   *
   * <p>호출자가 원문을 다시 해싱하지 않게 한다 — 원문이 한 번 더 지나가는 지점을 만들지 않고, 발급과 저장이 서로 다른 해석 경로를 갖지 않게 하려는 것이다.
   */
  public String tokenHash() {
    return this.tokenHash;
  }

  public String jti() {
    return this.jti;
  }

  public AccountActionPurpose purpose() {
    return this.purpose;
  }

  public long issuedAtEpochSecond() {
    return this.issuedAtEpochSecond;
  }

  public long expiresAtEpochSecond() {
    return this.expiresAtEpochSecond;
  }

  /** 토큰 원문을 비교 대상에서 제외한다. 같은 발급인지는 용도와 {@code jti}가 결정한다. */
  @Override
  public boolean equals(Object other) {
    return other instanceof IssuedActionToken issued
        && this.purpose == issued.purpose
        && this.jti.equals(issued.jti);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.purpose, this.jti);
  }

  @Override
  public String toString() {
    return REDACTED;
  }
}
