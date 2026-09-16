package com.itda.user.service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 존재하지 않는 계정에도 비밀번호 검증을 한 번 수행하기 위한 미끼 해시 (API 명세 3.5절 계정 열거 방지).
 *
 * <p>계정이 없을 때 Argon2id 없이 곧장 실패로 끝내면 응답이 눈에 띄게 빨라진다. 그 차이만으로 "이 아이디는 존재한다"를 읽어 낼 수 있으므로, 없는 계정에도 같은
 * 종류의 검증을 한 번 수행한다.
 *
 * <p>해시는 프로세스마다 CSPRNG로 만든 임의 비밀에서 <b>한 번만</b> 만들어 재사용한다. 값을 소스에 적어 두면 그것이 곧 공유 비밀이 되고, 매번 새로 만들면
 * 해시 비용을 두 번 치른다. 저장된 해시와 같은 Argon2id 파라미터를 쓰도록 운영과 같은 {@link PasswordHasher}로 만든다.
 *
 * <p><b>정확히 같은 응답 시간을 보장하지 않는다.</b> DB 조회 유무 등 남는 차이가 있고, 고정 지연을 넣어 시간을 맞추지도 않는다. 목표는 "검증을 건너뛰어 생기는
 * 눈에 띄는 차이"를 없애는 것이다.
 *
 * <p>원문 비밀과 encoded 해시를 로그·예외 message·{@code toString()}에 남기지 않는다.
 */
public class DecoyPasswordHash {

  /** 미끼 비밀의 길이. 추측 가능한 값이 되지 않을 만큼이면 충분하다. */
  private static final int SECRET_BYTES = 32;

  private final String encodedHash;

  public DecoyPasswordHash(PasswordHasher passwordHasher) {
    this.encodedHash = passwordHasher.hash(newSecret()).value();
  }

  /** 비교에 쓸 encoded 해시. 어떤 요청에서도 일치하지 않는다. */
  public String value() {
    return this.encodedHash;
  }

  private static String newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    new SecureRandom().nextBytes(secret);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
  }

  @Override
  public String toString() {
    return "DecoyPasswordHash[REDACTED]";
  }
}
