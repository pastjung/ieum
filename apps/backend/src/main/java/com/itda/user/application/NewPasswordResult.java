package com.itda.user.application;

import com.itda.user.application.port.out.EncodedPassword;
import com.itda.user.domain.PasswordPolicyViolation;

/**
 * 새 비밀번호 처리의 닫힌 결과.
 *
 * <p>정책 위반과 조회 장애를 예외가 아니라 값으로 돌려준다. 둘 다 예상되는 결과이고 서로 다른 HTTP 응답({@code 422 INVALID_PASSWORD} vs
 * {@code 503 PASSWORD_POLICY_UNAVAILABLE})으로 이어지므로, 호출자가 빠뜨릴 수 없게 sealed 타입으로 고정한다.
 *
 * <p>어느 구현에도 비밀번호 원문·정규화 값이 들어 있지 않다. {@code Hashed}가 담는 {@link EncodedPassword}의 {@code
 * toString()}도 가려진 값만 반환하므로 결과를 그대로 찍어도 해시가 노출되지 않는다.
 */
public sealed interface NewPasswordResult {

  /** 정책을 통과해 해시를 만들었다. */
  record Hashed(EncodedPassword encodedPassword) implements NewPasswordResult {}

  /** 결정적인 정책 위반이다. 해시를 만들지 않았다. */
  record Violated(PasswordPolicyViolation violation) implements NewPasswordResult {}

  /** 유출 조회에 실패해 정책을 완성하지 못했다. 위반으로 단정하지 않으며 해시도 만들지 않았다. */
  record Unavailable() implements NewPasswordResult {}
}
