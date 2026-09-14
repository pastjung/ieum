package com.itda.global.config;

import java.util.regex.Pattern;

/**
 * 로그 한 건의 최종 출력 문자열에서 저장소 접속 정보와 계정 식별자를 가린다.
 *
 * <p>이 서비스가 message를 고칠 수 없는 로그가 원문을 남긴다. Hibernate의 기동 연결 정보 로그에는 MariaDB driver가 사용자·비밀번호를 붙인
 * JDBC URL과 기본 catalog가, MariaDB driver와 Hibernate의 UNIQUE 위반 로그에는 가입 요청의 username·email이 담긴다.
 * logger를 끄거나 level을 올리면 저장소 오류가 났다는 사실·logger·level·requestId까지 사라지므로, 출력 직전에 값만 가린다.
 *
 * <p>JDBC URL과 접속 계정 파라미터는 값의 끝을 믿지 않고 같은 줄 끝까지 가린다. driver는 사용자·비밀번호를 인코딩하지 않고 URL에 붙이며 비밀번호에는
 * 공백·괄호·따옴표·구분 문자가 들어갈 수 있다. 경계를 추측해 값 일부를 남기느니 그 줄에 뒤따르는 진단 문구를 잃는 편을 택한다.
 *
 * <p>모든 규칙은 CR·LF를 넘지 않는다. 반복 구간은 단일 문자 class를 소유 수량자로 끝까지 읽거나 길이를 제한하므로 입력 길이에 비례해 끝나고, 이미 가려진 출력에
 * 다시 적용해도 결과가 바뀌지 않는다.
 */
final class SensitiveLogMasker {

  static final String REDACTED = "[REDACTED]";

  /** Hibernate 연결 정보 로그의 JDBC URL 줄. 대괄호 안을 해석하지 않고 레이블 뒤를 줄 끝까지 가린다. */
  private static final Pattern HIBERNATE_JDBC_URL =
      Pattern.compile("(?i)(database jdbc url )\\[[^\\r\\n]*+");

  /** Hibernate 연결 정보 로그의 기본 catalog/schema. MariaDB에서는 database 이름이다. */
  private static final Pattern DEFAULT_CATALOG_SCHEMA =
      Pattern.compile("(?i)(default catalog/schema:)[^\\r\\n]*+");

  /** 그 밖의 JDBC URL(예외 message 등). scheme 뒤를 같은 줄 끝까지 가린다. */
  private static final Pattern JDBC_URL =
      Pattern.compile("(?i)\\b(jdbc:[a-z][a-z0-9]{0,30}):[^\\r\\n]++");

  /** URL 밖에 따로 찍힌 접속 계정 파라미터. 값의 끝을 알 수 없으므로 이름 뒤를 같은 줄 끝까지 가린다. */
  private static final Pattern CREDENTIAL_PARAMETER =
      Pattern.compile("(?i)(?<![a-z0-9_.-])(password|passwd|pwd|user|username)=[^\\r\\n]*+");

  /**
   * MariaDB UNIQUE 위반 message의 값. 오류 번호와 key 이름은 진단에 필요하므로 남긴다.
   *
   * <p>값 끝을 첫 {@code ' for key}로 판정한다. username·email 형식에는 이 문자열이 들어갈 수 없고, 값 안의 작은따옴표에서 멈추지 않는다.
   */
  private static final Pattern DUPLICATE_ENTRY =
      Pattern.compile("(?i)(duplicate entry ')[^\\r\\n]{0,1024}?(' for key\\b)");

  /** 위 형태로 끝나지 않는 message. 값의 경계를 알 수 없으므로 줄 끝까지 가린다(fail-closed). */
  private static final Pattern UNTERMINATED_DUPLICATE_ENTRY =
      Pattern.compile("(?i)(duplicate entry ')(?!\\[REDACTED\\]' for key\\b)[^\\r\\n]*+");

  private SensitiveLogMasker() {}

  /**
   * 민감한 값을 {@link #REDACTED}로 바꾼 문자열을 돌려준다. 가릴 값이 없으면 입력을 그대로 돌려준다.
   *
   * <p>순서가 의미를 가진다. Hibernate 고정 형식을 일반 JDBC URL보다 먼저 처리해야 {@code Database JDBC URL [REDACTED]}가
   * 대괄호 중복 없이 남는다.
   *
   * @param text 로그 한 건의 최종 출력. 여러 줄(예외 stack trace)일 수 있다
   */
  static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String masked = HIBERNATE_JDBC_URL.matcher(text).replaceAll("$1" + REDACTED);
    masked = DEFAULT_CATALOG_SCHEMA.matcher(masked).replaceAll("$1 " + REDACTED);
    masked = JDBC_URL.matcher(masked).replaceAll("$1:" + REDACTED);
    masked = CREDENTIAL_PARAMETER.matcher(masked).replaceAll("$1=" + REDACTED);
    masked = DUPLICATE_ENTRY.matcher(masked).replaceAll("$1" + REDACTED + "$2");
    return UNTERMINATED_DUPLICATE_ENTRY.matcher(masked).replaceAll("$1" + REDACTED);
  }
}
