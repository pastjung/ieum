package com.itda.user.infrastructure;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 계정 작업 토큰 전용 key ring (API 명세 3.6.1절 「서명과 key ring」).
 *
 * <p>Access·Refresh ring과 별개다. 이메일 인증과 비밀번호 재설정은 수명이 짧고 성격이 같아 계약대로 <b>한 ring을 공유</b>하며, 종류 분리는
 * {@code typ}·{@code aud}·{@code purpose}와 전용 발급기·검증기가 담당한다.
 *
 * <p>{@link AccessKeyRing}·{@link RefreshKeyRing}과 파싱 규칙이 같지만 합치지 않았다. 세 ring을 공통 타입으로 묶으면 "각 ring은
 * 자기 종류의 토큰만 서명·검증한다"는 계약이 타입이 아니라 호출 규약으로 내려가고, ring을 바꿔 넣는 실수가 컴파일 단계에서 드러나지 않는다.
 */
public final class ActionKeyRing {

  /** API 명세 3.6.1절: CSPRNG로 생성한 256비트 이상. */
  static final int MIN_KEY_BYTES = 32;

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String ENTRY_DELIMITER = ",";
  private static final char KID_KEY_SEPARATOR = ':';

  private final String activeKid;
  private final Map<String, SecretKey> keysByKid;

  private ActionKeyRing(String activeKid, Map<String, SecretKey> keysByKid) {
    this.activeKid = activeKid;
    this.keysByKid = Map.copyOf(keysByKid);
  }

  /** 형식·길이·중복이 어긋나면 기동 단계에서 실패한다 — 잘못된 key로 뜬 서버는 서명을 계속 만들어 낸다. */
  public static ActionKeyRing from(ActionJwtProperties properties) {
    Map<String, SecretKey> keys = new LinkedHashMap<>();
    keys.put(properties.activeKid(), toSecretKey(properties.activeKey()));
    for (String entry : properties.retiredKeys().split(ENTRY_DELIMITER)) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      addRetired(keys, trimmed);
    }
    return new ActionKeyRing(properties.activeKid(), keys);
  }

  private static void addRetired(Map<String, SecretKey> keys, String entry) {
    int separator = entry.indexOf(KID_KEY_SEPARATOR);
    if (separator <= 0 || separator == entry.length() - 1) {
      throw new IllegalArgumentException(
          "itda.security.jwt.action.retired-keys는 kid:base64url 항목을 쉼표로 이어 붙인 형식이어야 합니다.");
    }
    String kid = entry.substring(0, separator);
    AccessJwtProperties.requireValidKid(kid);
    if (keys.containsKey(kid)) {
      throw new IllegalArgumentException("action key ring 안에서 kid가 중복됩니다.");
    }
    keys.put(kid, toSecretKey(entry.substring(separator + 1)));
  }

  private static SecretKey toSecretKey(String base64UrlKey) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(base64UrlKey);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("action key를 Base64URL로 디코딩하지 못했습니다.");
    }
    if (decoded.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("action key는 디코딩 후 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다.");
    }
    return new SecretKeySpec(decoded, HMAC_ALGORITHM);
  }

  public String activeKid() {
    return this.activeKid;
  }

  public SecretKey activeKey() {
    return this.keysByKid.get(this.activeKid);
  }

  /** action ring 안에서만 조회한다. 없으면 빈 값이며 다른 ring으로 넓히지 않는다. */
  public Optional<SecretKey> findByKid(String kid) {
    return Optional.ofNullable(this.keysByKid.get(kid));
  }

  /** 세 ring 교차 중복 검사를 위해 노출한다. key 값은 내보내지 않는다. */
  public Set<String> kids() {
    return this.keysByKid.keySet();
  }

  int size() {
    return this.keysByKid.size();
  }

  @Override
  public String toString() {
    return "ActionKeyRing[REDACTED]";
  }
}
