package com.itda.global.security;

import java.util.regex.Pattern;

/**
 * JWT 서명 key의 {@code kid} 형식 (API 명세 3.6.1절).
 *
 * <p>Access·Refresh·action 세 ring의 설정과 key ring이 같은 형식 규칙을 쓴다. 이 클래스는 형식만 검사하며 어느 토큰의 발급·검증도 맡지
 * 않는다.
 */
public final class JwtKeyContract {

  /** kid 형식. 쉼표와 콜론은 retired key 목록의 구분자이므로 허용하지 않는다. 길이는 UUID(36자)를 담고도 남게 잡되 무한정 늘어나지 않게 제한한다. */
  private static final Pattern KID_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]{1,64}$");

  private JwtKeyContract() {}

  public static void requireValidKid(String kid) {
    if (kid == null || !KID_PATTERN.matcher(kid).matches()) {
      throw new IllegalArgumentException("서명 key의 kid는 1~64자의 영문·숫자·'.', '_', '~', '-' 조합이어야 합니다.");
    }
  }
}
