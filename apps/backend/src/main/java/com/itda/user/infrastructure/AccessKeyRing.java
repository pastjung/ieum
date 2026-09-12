package com.itda.user.infrastructure;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Access 전용 key ring (API 명세 3.6.1절 「서명과 key ring」·「key rotation」).
 *
 * <p>서명은 항상 active key로만 하고, 검증은 active와 retired 전체에서 {@code kid}로 조회한다. retired key를 두는 이유는 회전
 * 직후에도 아직 수명이 남은 토큰을 받아 줘야 하기 때문이다.
 *
 * <p>이 ring은 Access 종류만 담는다. Refresh·action ring은 별도이며 여기서 조회되지 않는다 — 한쪽의 유출 회전이 다른 쪽을 끌고 들어가지 않게
 * 하는 것이 ring 분리의 목적이다.
 *
 * <p>이번 이슈는 Access ring 내부의 {@code kid} 유일성만 강제한다. 세 ring 전체의 유일성은 Refresh·action ring이 생기는 이슈에서 함께
 * 보장해야 한다.
 */
public final class AccessKeyRing {

  /** API 명세 3.6.1절: CSPRNG로 생성한 256비트 이상. */
  static final int MIN_KEY_BYTES = 32;

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String ENTRY_DELIMITER = ",";
  private static final char KID_KEY_SEPARATOR = ':';

  private final String activeKid;
  private final Map<String, SecretKey> keysByKid;

  private AccessKeyRing(String activeKid, Map<String, SecretKey> keysByKid) {
    this.activeKid = activeKid;
    this.keysByKid = Map.copyOf(keysByKid);
  }

  /**
   * 설정에서 ring을 만든다. 형식·길이·중복이 어긋나면 기동 단계에서 실패한다 — 잘못된 key로 뜬 서버는 서명을 계속 만들어 내기 때문이다.
   *
   * @param retiredKeys {@code kid1:base64url,kid2:base64url} 형식. 빈 문자열이면 retired key가 없다
   */
  public static AccessKeyRing from(AccessJwtProperties properties) {
    Map<String, SecretKey> keys = new LinkedHashMap<>();
    keys.put(properties.activeKid(), toSecretKey(properties.activeKey()));
    for (String entry : properties.retiredKeys().split(ENTRY_DELIMITER)) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      addRetired(keys, trimmed);
    }
    return new AccessKeyRing(properties.activeKid(), keys);
  }

  private static void addRetired(Map<String, SecretKey> keys, String entry) {
    int separator = entry.indexOf(KID_KEY_SEPARATOR);
    if (separator <= 0 || separator == entry.length() - 1) {
      throw new IllegalArgumentException(
          "itda.security.jwt.access.retired-keys는 kid:base64url 항목을 쉼표로 이어 붙인 형식이어야 합니다.");
    }
    String kid = entry.substring(0, separator);
    AccessJwtProperties.requireValidKid(kid);
    if (keys.containsKey(kid)) {
      // kid가 겹치면 어느 key로 검증할지 결정할 수 없다. active와 retired 사이의 충돌도 포함한다.
      throw new IllegalArgumentException("Access key ring 안에서 kid가 중복됩니다.");
    }
    keys.put(kid, toSecretKey(entry.substring(separator + 1)));
  }

  /** 예외 message에 key 값을 넣지 않는다. */
  private static SecretKey toSecretKey(String base64UrlKey) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(base64UrlKey);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Access key를 Base64URL로 디코딩하지 못했습니다.");
    }
    if (decoded.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("Access key는 디코딩 후 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다.");
    }
    return new SecretKeySpec(decoded, HMAC_ALGORITHM);
  }

  public String activeKid() {
    return this.activeKid;
  }

  public SecretKey activeKey() {
    return this.keysByKid.get(this.activeKid);
  }

  /** Access ring 안에서만 조회한다. 없으면 빈 값이며 다른 ring으로 넓히지 않는다. */
  public Optional<SecretKey> findByKid(String kid) {
    return Optional.ofNullable(this.keysByKid.get(kid));
  }

  int size() {
    return this.keysByKid.size();
  }

  @Override
  public String toString() {
    return "AccessKeyRing[REDACTED]";
  }
}
