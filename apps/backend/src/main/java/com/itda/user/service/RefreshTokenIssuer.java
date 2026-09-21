package com.itda.user.service;

import com.itda.user.repository.RefreshTokenStore;
import java.util.Objects;

/**
 * Refresh Token 발급 outbound port. 구현은 {@link NimbusRefreshTokenCodec}이다.
 *
 * <p>Access와 같은 port로 합치지 않는다. key ring·{@code typ}·{@code aud}·필수 claim이 모두 다르고, 계약(API 명세 3.6.1절
 * 「종류 분리」)이 종류마다 다른 코드 경로를 타도록 요구한다.
 */
public interface RefreshTokenIssuer {

  /**
   * compact JWS와 함께 Redis 등록에 필요한 식별자·시각을 돌려준다.
   *
   * @return 반환값의 compact 토큰은 원문이다. 저장·로그 대상이 아니며 호출자에게 전달하기 위해서만 존재한다
   */
  IssuedToken issue(Claims claims);

  /**
   * Refresh Token 발급 입력 (API 명세 3.6.1절 Payload claim).
   *
   * <p>Access와 달리 {@code familyId}를 싣고 {@code role}은 싣지 않는다. Refresh는 권한 판단에 쓰이지 않고 회전 계열을 식별하는
   * 용도이기 때문이다.
   *
   * <p>{@code jti}·{@code iat}·{@code exp}는 여기에 담지 않는다 — 서버가 발급 시점에 직접 만든다.
   *
   * <p>{@code toString()}을 재정의해 사용자 식별자와 세대 값이 로그로 새지 않게 한다. 출력 문자열은 통합 전 top-level 타입 이름을 유지한다.
   */
  record Claims(long userId, String familyId, long authVersion) {

    public Claims {
      if (userId <= 0) {
        throw new IllegalArgumentException("사용자 식별자가 올바르지 않습니다.");
      }
      // 식별자 규칙은 한 곳에만 둔다. 발급 입력·발급 결과·검증 결과가 같은 형식을 따라야
      // 어느 한쪽만 느슨해져 Redis 키가 되는 값이 새는 일이 없다.
      RefreshTokenStore.TokenRecord.requireFamilyId(familyId);
      if (authVersion < 0) {
        throw new IllegalArgumentException("인증 세대 값이 올바르지 않습니다.");
      }
    }

    @Override
    public String toString() {
      return "RefreshTokenClaims[REDACTED]";
    }
  }

  /**
   * 방금 발급한 Refresh Token과 Redis 등록에 필요한 값.
   *
   * <p>compact 문자열만 돌려주지 않는 이유: 호출자가 {@code jti}·{@code familyId}·시각을 알아야 Redis 상태를 등록하고 회전할 수 있는데,
   * 그러려고 방금 만든 토큰을 다시 파싱하면 발급과 저장이 서로 다른 해석 경로를 갖게 된다. 발급기가 정한 값을 그대로 넘긴다.
   *
   * <p>record 기본 구현을 쓰지 않는 이유는 {@code compactToken} 때문이다. 기본 {@code toString()}·{@code equals()}는
   * 토큰 원문을 그대로 드러내고, 특히 테스트 실패 message와 디버거에서 새기 쉽다. 값 비교는 {@code jti}로 충분하다.
   */
  final class IssuedToken {

    /** 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
    private static final String REDACTED = "IssuedRefreshToken[REDACTED]";

    private final String compactToken;
    private final String tokenHash;
    private final String jti;
    private final String familyId;
    private final long issuedAtEpochSecond;
    private final long expiresAtEpochSecond;

    /**
     * @param tokenHash Redis에 저장할 값. <b>발급기가 compact 원문으로 직접 계산해 넘긴다</b> — 호출자가 계산하려면 원문을 들고 다니며 해시
     *     구현을 import해야 하고, 그러면 원문이 상위 계층으로 퍼진다
     */
    public IssuedToken(
        String compactToken,
        String tokenHash,
        String jti,
        String familyId,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond) {
      this.compactToken = requireText(compactToken, "compact 토큰");
      // 식별자·해시 규칙은 RefreshTokenStore.TokenRecord 한 곳에만 둔다 — 발급 결과가 저장 계약보다 느슨하면
      // 발급은 됐는데 저장할 수 없는 값이 만들어진다.
      this.tokenHash = RefreshTokenStore.TokenRecord.requireTokenHash(tokenHash);
      this.jti = RefreshTokenStore.TokenRecord.requireJti(jti);
      this.familyId = RefreshTokenStore.TokenRecord.requireFamilyId(familyId);
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

    /** Redis에 저장하는 값. 원문이 아니라 이 값만 저장소로 간다. */
    public String tokenHash() {
      return this.tokenHash;
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
      return other instanceof IssuedToken issued && this.jti.equals(issued.jti);
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
}
