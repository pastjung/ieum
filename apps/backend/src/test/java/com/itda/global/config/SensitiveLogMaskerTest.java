package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 로그 최종 출력 문자열에서 저장소 접속 정보와 계정 식별자가 가려지는지 고정한다.
 *
 * <p>비교용 원문은 실행할 때마다 합성한다. 실패 메시지에 원문이 찍히지 않도록 포함 여부는 boolean으로만 단언하고, 기대 문자열과의 비교도 실제 값을 출력하지 않는
 * 방식으로 한다.
 */
class SensitiveLogMaskerTest {

  private static String synthetic(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private static String syntheticEmail() {
    return synthetic("u") + "@" + "example" + ".invalid";
  }

  private static void assertHidden(String masked, String original, String description) {
    assertThat(masked.contains(original)).as(description + " 원문이 남지 않는다").isFalse();
  }

  private static void assertMaskedAs(String masked, String expected) {
    assertThat(masked.equals(expected)).as("마스킹 결과가 기대 형태와 같다: " + expected).isTrue();
  }

  @Test
  void JDBC_URL의_주소_database_사용자_비밀번호를_모두_가린다() {
    String host = synthetic("db-") + ".internal.invalid";
    String database = synthetic("db");
    String user = synthetic("dbu");
    String secret = synthetic("pw");

    String masked =
        SensitiveLogMasker.mask(
            "\tDatabase JDBC URL [jdbc:mariadb://"
                + host
                + ":3306/"
                + database
                + "?user="
                + user
                + "&password="
                + secret
                + "&connectionTimeZone=UTC]");

    assertHidden(masked, host, "DB 주소");
    assertHidden(masked, database, "DB 이름");
    assertHidden(masked, user, "DB 사용자");
    assertHidden(masked, secret, "DB 비밀번호");
    assertMaskedAs(masked, "\tDatabase JDBC URL [REDACTED]");
  }

  @Test
  void 연결_정보_로그의_기본_catalog_schema_값을_가리고_다른_항목은_남긴다() {
    String database = synthetic("db");

    String masked =
        SensitiveLogMasker.mask(
            "\tDatabase version: 11.8.9\n\tDefault catalog/schema: "
                + database
                + "/undefined\n\tAutocommit mode: undefined/unknown");

    assertHidden(masked, database, "DB 이름");
    assertMaskedAs(
        masked,
        "\tDatabase version: 11.8.9\n\tDefault catalog/schema: [REDACTED]\n\tAutocommit mode: undefined/unknown");
  }

  @Test
  void JDBC_URL은_파라미터_순서와_대소문자가_달라도_같은_줄_끝까지_가린다() {
    String user = synthetic("dbu");
    String secret = synthetic("pw");

    String masked =
        SensitiveLogMasker.mask(
            "connecting to JDBC:MariaDB://"
                + synthetic("h")
                + "/app?Password="
                + secret
                + "&connectTimeout=5&USER="
                + user
                + " then retrying");

    assertHidden(masked, user, "DB 사용자");
    assertHidden(masked, secret, "DB 비밀번호");
    assertMaskedAs(masked, "connecting to JDBC:MariaDB:[REDACTED]");
  }

  @Test
  void URL_밖의_접속_계정_파라미터는_첫_이름부터_같은_줄_끝까지_가리고_다음_줄은_남긴다() {
    String user = synthetic("dbu");
    String secret = synthetic("pw");

    String masked =
        SensitiveLogMasker.mask(
            "options ?User="
                + user
                + "&PASSWORD="
                + secret
                + "&connectionTimeZone=UTC;sslMode=trust status=ok\nnext line kept");

    assertHidden(masked, user, "DB 사용자");
    assertHidden(masked, secret, "DB 비밀번호");
    assertMaskedAs(masked, "options ?User=[REDACTED]\nnext line kept");
  }

  @Test
  void UNIQUE_위반의_username_값만_가리고_오류_번호와_key_이름은_남긴다() {
    String username = synthetic("user");

    String masked =
        SensitiveLogMasker.mask(
            "Error: 1062-23000: Duplicate entry '"
                + username
                + "' for key 'UK6dotkott2kjsp8vw4d0m25fb7'");

    assertHidden(masked, username, "username");
    assertMaskedAs(
        masked,
        "Error: 1062-23000: Duplicate entry '[REDACTED]' for key 'UK6dotkott2kjsp8vw4d0m25fb7'");
  }

  @Test
  void UNIQUE_위반의_email_값만_가리고_작은따옴표가_섞여도_원문을_남기지_않는다() {
    String email = syntheticEmail();
    String quoted = "o'" + email;

    String masked =
        SensitiveLogMasker.mask("(conn=6) Duplicate entry '" + email + "' for key 'UK_email'");
    String maskedQuoted =
        SensitiveLogMasker.mask("(conn=6) Duplicate entry '" + quoted + "' for key 'UK_email'");

    assertHidden(masked, email, "email");
    assertMaskedAs(masked, "(conn=6) Duplicate entry '[REDACTED]' for key 'UK_email'");
    assertHidden(maskedQuoted, email, "작은따옴표가 섞인 email");
    assertMaskedAs(maskedQuoted, "(conn=6) Duplicate entry '[REDACTED]' for key 'UK_email'");
  }

  @Test
  void 민감정보가_없는_로그와_requestId는_그대로_둔다() {
    String requestId = UUID.randomUUID().toString();
    String tracing =
        "2026-09-14T15:50:55.675+09:00  INFO 1 --- [itda-backend] [exec-1] ["
            + requestId
            + "] c.i.global.web.RequestTracingFilter      : http_request method=POST path=/api/v1/users status=409 durationMs=12";
    String worker = "메일 작업을 정리했습니다. failed=0 deleted=0";
    String selection = "메일 작업을 선점하지 못했습니다. outboxId=42 purpose=EMAIL_VERIFICATION userId=7";

    assertThat(SensitiveLogMasker.mask(tracing)).isEqualTo(tracing);
    assertThat(SensitiveLogMasker.mask(worker)).isEqualTo(worker);
    assertThat(SensitiveLogMasker.mask(selection)).isEqualTo(selection);
  }

  @Test
  void 한_줄의_여러_패턴과_여러_줄을_모두_가리고_줄_구조는_유지한다() {
    String username = synthetic("user");
    String email = syntheticEmail();
    String secret = synthetic("pw");
    String otherSecret = synthetic("pw");

    String masked =
        SensitiveLogMasker.mask(
            "Duplicate entry '"
                + username
                + "' for key 'a' / Duplicate entry '"
                + email
                + "' for key 'b' jdbc:mariadb://"
                + synthetic("h")
                + "/db?password="
                + secret
                + "\nnext password="
                + otherSecret
                + "\nsafe line");

    assertHidden(masked, username, "username");
    assertHidden(masked, email, "email");
    assertHidden(masked, secret, "첫째 줄 비밀번호");
    assertHidden(masked, otherSecret, "둘째 줄 비밀번호");
    assertMaskedAs(
        masked,
        "Duplicate entry '[REDACTED]' for key 'a' / Duplicate entry '[REDACTED]' for key 'b'"
            + " jdbc:mariadb:[REDACTED]\nnext password=[REDACTED]\nsafe line");
  }

  @Test
  void 불완전하거나_빈_값도_원문을_남기지_않는다() {
    String email = syntheticEmail();

    String unterminated = SensitiveLogMasker.mask("Duplicate entry '" + email);
    String brokenByNewline =
        SensitiveLogMasker.mask("Duplicate entry '" + email + "\n' for key 'k'");

    assertHidden(unterminated, email, "닫히지 않은 값");
    assertMaskedAs(unterminated, "Duplicate entry '[REDACTED]");
    assertHidden(brokenByNewline, email, "줄바꿈으로 끊긴 값");
    assertMaskedAs(brokenByNewline, "Duplicate entry '[REDACTED]\n' for key 'k'");
    assertThat(SensitiveLogMasker.mask("Duplicate entry '' for key 'k'"))
        .isEqualTo("Duplicate entry '[REDACTED]' for key 'k'");
    String emptyCredential = "pass" + "word" + "=";
    assertThat(SensitiveLogMasker.mask(emptyCredential))
        .isEqualTo(emptyCredential + SensitiveLogMasker.REDACTED);
    assertThat(SensitiveLogMasker.mask("jdbc:mariadb:")).isEqualTo("jdbc:mariadb:");
    assertThat(SensitiveLogMasker.mask("")).isEmpty();
    assertThat(SensitiveLogMasker.mask(null)).isNull();
  }

  @Test
  void 매우_긴_입력과_되돌림을_유발하는_입력도_제한_시간_안에_가린다() {
    String email = syntheticEmail();
    String secret = synthetic("pw");
    String unterminated = "Duplicate entry '" + "a".repeat(200_000) + email;
    String backtracking =
        ("Duplicate entry '" + "x".repeat(1_000) + "' for kex ").repeat(2_000) + email;
    String longUrl = "jdbc:mariadb://" + "h".repeat(100_000) + "/db?password=" + secret;

    String[] masked =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () ->
                new String[] {
                  SensitiveLogMasker.mask(unterminated),
                  SensitiveLogMasker.mask(backtracking),
                  SensitiveLogMasker.mask(longUrl)
                });

    assertHidden(masked[0], email, "긴 닫히지 않은 값");
    assertHidden(masked[1], email, "되돌림 입력 끝의 값");
    assertHidden(masked[2], secret, "긴 URL의 비밀번호");
    assertMaskedAs(masked[2], "jdbc:mariadb:[REDACTED]");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "[", "]", "'", "\"", "<", ">", "&", ";", ","})
  void 일반_JDBC_URL은_자격_증명에_경계_문자가_있어도_같은_줄_끝까지_가리고_다음_줄은_남긴다(String boundary) {
    String host = synthetic("h");
    String database = synthetic("db");
    String userLeft = synthetic("ul");
    String userRight = synthetic("ur");
    String secretLeft = synthetic("pl");
    String secretRight = synthetic("pr");

    String masked =
        SensitiveLogMasker.mask(
            "Cannot connect to jdbc:mariadb://"
                + host
                + ":3306/"
                + database
                + "?user="
                + userLeft
                + boundary
                + userRight
                + "&password="
                + secretLeft
                + boundary
                + secretRight
                + "&connectionTimeZone=UTC] retrying\nnext line kept");

    assertHidden(masked, userLeft, "DB 사용자 왼쪽 조각");
    assertHidden(masked, userRight, "DB 사용자 오른쪽 조각");
    assertHidden(masked, secretLeft, "DB 비밀번호 왼쪽 조각");
    assertHidden(masked, secretRight, "DB 비밀번호 오른쪽 조각");
    assertHidden(masked, host, "DB 주소");
    assertHidden(masked, database, "DB 이름");
    assertMaskedAs(masked, "Cannot connect to jdbc:mariadb:[REDACTED]\nnext line kept");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "[", "]", "'", "\"", "<", ">", "&", ";", ","})
  void Hibernate_연결_정보의_JDBC_URL은_경계_문자가_있어도_레이블_뒤를_통째로_가린다(String boundary) {
    String host = synthetic("h");
    String database = synthetic("db");
    String userLeft = synthetic("ul");
    String userRight = synthetic("ur");
    String secretLeft = synthetic("pl");
    String secretRight = synthetic("pr");

    String masked =
        SensitiveLogMasker.mask(
            "Database info:\n\tDatabase JDBC URL [jdbc:mariadb://"
                + host
                + ":3306/"
                + database
                + "?user="
                + userLeft
                + boundary
                + userRight
                + "&password="
                + secretLeft
                + boundary
                + secretRight
                + "&connectionTimeZone=UTC]\n\tDatabase driver: MariaDB Connector/J");

    assertHidden(masked, userLeft, "DB 사용자 왼쪽 조각");
    assertHidden(masked, userRight, "DB 사용자 오른쪽 조각");
    assertHidden(masked, secretLeft, "DB 비밀번호 왼쪽 조각");
    assertHidden(masked, secretRight, "DB 비밀번호 오른쪽 조각");
    assertHidden(masked, host, "DB 주소");
    assertHidden(masked, database, "DB 이름");
    assertMaskedAs(
        masked,
        "Database info:\n\tDatabase JDBC URL [REDACTED]\n\tDatabase driver: MariaDB Connector/J");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "[", "]", "'", "\"", "<", ">", "&", ";", ","})
  void URL_밖의_접속_계정_값은_경계_문자가_있어도_같은_줄_끝까지_가리고_다음_줄은_남긴다(String boundary) {
    for (String name : new String[] {"user", "username", "pass" + "word", "passwd", "pwd"}) {
      String left = synthetic("l");
      String right = synthetic("r");

      String masked =
          SensitiveLogMasker.mask(
              "options " + name + "=" + left + boundary + right + " status=ok\nnext line kept");

      assertHidden(masked, left, name + " 값 왼쪽 조각");
      assertHidden(masked, right, name + " 값 오른쪽 조각");
      assertMaskedAs(masked, "options " + name + "=[REDACTED]\nnext line kept");
    }
  }

  @Test
  void 이미_가려진_알려진_형식은_다시_가려도_바뀌지_않는다() {
    String[] alreadyMasked = {
      "pass" + "word=" + SensitiveLogMasker.REDACTED,
      "user=" + SensitiveLogMasker.REDACTED,
      "jdbc:mariadb:" + SensitiveLogMasker.REDACTED,
      "(conn=6) Duplicate entry '" + SensitiveLogMasker.REDACTED + "' for key 'UK_email'",
      "\tDatabase JDBC URL " + SensitiveLogMasker.REDACTED,
      "\tDefault catalog/schema: " + SensitiveLogMasker.REDACTED
    };

    for (String line : alreadyMasked) {
      assertThat(SensitiveLogMasker.mask(line)).isEqualTo(line);
      assertThat(SensitiveLogMasker.mask(SensitiveLogMasker.mask(line))).isEqualTo(line);
    }
  }
}
