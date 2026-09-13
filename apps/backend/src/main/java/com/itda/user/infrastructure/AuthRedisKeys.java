package com.itda.user.infrastructure;

/**
 * 인증 상태 Redis 키를 만드는 유일한 지점 (API 명세 3.6.1절 「Redis 키 표기」).
 *
 * <p>중괄호는 Redis hash tag 문법 그대로이며 {@code userId} 값에만 적용한다. 한 연산이 건드리는 키가 같은 slot에 모이게 해, 현재 단일 인스턴스
 * 전제에서도 키 설계가 Cluster의 same-slot 제약을 깨지 않게 한다.
 *
 * <p>{@code userId}는 10진수 문자열로만 다룬다. Lua number로 넘기면 BIGINT 정밀도를 잃는다.
 *
 * <p>키 문자열은 이 클래스와 어댑터 밖으로 나가지 않는다. application은 닫힌 결과만 본다.
 */
public class AuthRedisKeys {

  private final String prefix;

  public AuthRedisKeys(AuthRedisProperties properties) {
    this.prefix = properties.keyPrefix();
  }

  /** {@code <prefix>:auth:{<userId>}:refresh:<jti>} */
  public String refreshToken(long userId, String jti) {
    return userNamespace(userId) + ":refresh:" + jti;
  }

  /** {@code <prefix>:auth:{<userId>}:family:<familyId>} */
  public String family(long userId, String familyId) {
    return userNamespace(userId) + ":family:" + familyId;
  }

  /** {@code <prefix>:auth:{<userId>}:families} */
  public String families(long userId) {
    return userNamespace(userId) + ":families";
  }

  private String userNamespace(long userId) {
    return this.prefix + ":auth:{" + Long.toString(userId) + "}";
  }
}
