package com.itda.user.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Redis에 저장할 {@code tokenHash}를 만든다 (API 명세 3.6.1절).
 *
 * <p>계약이 "compact JWT 원문의 ASCII 바이트에 대한 SHA-256을 Base64URL(padding 없음)로 인코딩"이라고 정했으므로 그대로 따른다.
 * compact JWS는 정의상 ASCII라 UTF-8과 결과가 같지만, 계약 문구를 코드에 그대로 옮겨 두면 나중에 인코딩을 두고 다툴 일이 없다.
 *
 * <p>토큰 원문은 이 계산 외에 어디에도 남기지 않는다. Redis에는 해시만 저장한다.
 */
public final class RefreshTokenHasher {

  private RefreshTokenHasher() {}

  public static String hash(String compactToken) {
    if (compactToken == null || compactToken.isEmpty()) {
      // 입력값을 message에 넣지 않는다.
      throw new IllegalArgumentException("해시할 토큰 값이 없습니다.");
    }
    byte[] digest = sha256().digest(compactToken.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 다이제스트를 사용할 수 없습니다.");
    }
  }
}
