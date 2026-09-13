package com.itda.user.infrastructure;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Refresh 전용 key ring (API 명세 3.6.1절 「서명과 key ring」).
 *
 * <p>Access ring과 별개다. Refresh는 14일 수명 때문에 retired key를 오래 들고 있어야 하고 Access는 회전 주기가 짧다. 두 종류가 key를
 * 공유하면 한쪽의 유출 대응 회전이 다른 쪽 전체를 끌고 들어간다.
 *
 * <p>{@link AccessKeyRing}과 파싱 규칙이 같지만 합치지 않았다. action ring까지 생기면 세 ring을 한 번에 공통화하는 편이 낫고, 이번 이슈에서
 * Access 구현을 함께 고치면 범위가 섞인다.
 */
public final class RefreshKeyRing {

  /** API 명세 3.6.1절: CSPRNG로 생성한 256비트 이상. */
  static final int MIN_KEY_BYTES = 32;

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String ENTRY_DELIMITER = ",";
  private static final char KID_KEY_SEPARATOR = ':';

  private final String activeKid;
  private final Map<String, SecretKey> keysByKid;

  private RefreshKeyRing(String activeKid, Map<String, SecretKey> keysByKid) {
    this.activeKid = activeKid;
    this.keysByKid = Map.copyOf(keysByKid);
  }

  /** 형식·길이·중복이 어긋나면 기동 단계에서 실패한다 — 잘못된 key로 뜬 서버는 서명을 계속 만들어 낸다. */
  public static RefreshKeyRing from(RefreshJwtProperties properties) {
    Map<String, SecretKey> keys = new LinkedHashMap<>();
    keys.put(properties.activeKid(), toSecretKey(properties.activeKey()));
    for (String entry : properties.retiredKeys().split(ENTRY_DELIMITER)) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      addRetired(keys, trimmed);
    }
    return new RefreshKeyRing(properties.activeKid(), keys);
  }

  private static void addRetired(Map<String, SecretKey> keys, String entry) {
    int separator = entry.indexOf(KID_KEY_SEPARATOR);
    if (separator <= 0 || separator == entry.length() - 1) {
      throw new IllegalArgumentException(
          "itda.security.jwt.refresh.retired-keys는 kid:base64url 항목을 쉼표로 이어 붙인 형식이어야 합니다.");
    }
    String kid = entry.substring(0, separator);
    AccessJwtProperties.requireValidKid(kid);
    if (keys.containsKey(kid)) {
      throw new IllegalArgumentException("Refresh key ring 안에서 kid가 중복됩니다.");
    }
    keys.put(kid, toSecretKey(entry.substring(separator + 1)));
  }

  private static SecretKey toSecretKey(String base64UrlKey) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(base64UrlKey);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Refresh key를 Base64URL로 디코딩하지 못했습니다.");
    }
    if (decoded.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("Refresh key는 디코딩 후 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다.");
    }
    return new SecretKeySpec(decoded, HMAC_ALGORITHM);
  }

  public String activeKid() {
    return this.activeKid;
  }

  public SecretKey activeKey() {
    return this.keysByKid.get(this.activeKid);
  }

  /** Refresh ring 안에서만 조회한다. 없으면 빈 값이며 다른 ring으로 넓히지 않는다. */
  public Optional<SecretKey> findByKid(String kid) {
    return Optional.ofNullable(this.keysByKid.get(kid));
  }

  /** Access ring과의 교차 중복 검사를 위해 노출한다. key 값은 내보내지 않는다. */
  public Set<String> kids() {
    return this.keysByKid.keySet();
  }

  int size() {
    return this.keysByKid.size();
  }

  @Override
  public String toString() {
    return "RefreshKeyRing[REDACTED]";
  }
}
