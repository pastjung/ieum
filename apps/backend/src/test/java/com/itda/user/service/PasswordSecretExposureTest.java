package com.itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.itda.support.log.CapturingAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 비밀번호 원문과 해시가 결과 객체·로그로 새어 나가지 않는지 확인한다 (API 명세 3.6.2절 「응답과 로그」, 보안 규칙).
 *
 * <p>실패 message에 비밀값을 직접 쓰지 않도록, 단언은 "포함되지 않는다"만 검사한다.
 */
class PasswordSecretExposureTest {

  private static final String PASSWORD = "충분히 긴 새 비밀번호 문구";

  private static String sha1Upper(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().withUpperCase().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-1 다이제스트를 사용할 수 없습니다.");
    }
  }

  @Test
  void EncodedPassword의_toString은_값을_가린다() {
    String hashValue = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2Fs$aGFzaGhhc2hoYXNo";

    PasswordHasher.EncodedPassword encodedPassword = PasswordHasher.EncodedPassword.of(hashValue);

    assertThat(encodedPassword.toString()).isEqualTo("EncodedPassword[REDACTED]");
    assertThat(encodedPassword.toString()).doesNotContain(hashValue);
    assertThat(encodedPassword.value()).isEqualTo(hashValue);
  }

  @Test
  void 성공_결과의_toString에는_원문도_해시도_없다() {
    Argon2idPasswordHasher passwordHasher =
        new Argon2idPasswordHasher(new Argon2idProperties(19456, 2, 1));
    RecordingCompromisedPasswordChecker checker = new RecordingCompromisedPasswordChecker();
    NewPasswordService.Result result =
        new NewPasswordService(checker, passwordHasher).prepare(PASSWORD);

    String hashValue = ((NewPasswordService.Result.Hashed) result).encodedPassword().value();

    assertThat(result.toString()).doesNotContain(PASSWORD).doesNotContain(hashValue);
    assertThat(result.toString()).contains("REDACTED");
  }

  @Test
  void 위반_결과와_장애_결과의_toString에도_원문이_없다() {
    RecordingCompromisedPasswordChecker checker = new RecordingCompromisedPasswordChecker();
    NewPasswordService newPasswordService =
        new NewPasswordService(checker, new RecordingPasswordHasher());

    NewPasswordService.Result tooShort = newPasswordService.prepare("짧은비밀번호");
    checker.willReturn(CompromisedPasswordChecker.CheckResult.COMPROMISED);
    NewPasswordService.Result compromised = newPasswordService.prepare(PASSWORD);
    checker.willReturn(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);
    NewPasswordService.Result unavailable = newPasswordService.prepare(PASSWORD);

    assertThat(tooShort.toString()).doesNotContain("짧은비밀번호");
    assertThat(compromised.toString()).doesNotContain(PASSWORD);
    assertThat(unavailable.toString()).doesNotContain(PASSWORD);
  }

  @Test
  void 손상된_해시_검증_로그에_해시도_비밀번호도_예외도_남기지_않는다() {
    String malformedHash = "$argon2id$v=19$m=99999999,t=2,p=1$c2FsdA$aGFzaA";
    CapturingAppender appender = CapturingAppender.attachTo(Argon2idPasswordHasher.class);
    try {
      new Argon2idPasswordHasher(new Argon2idProperties(19456, 2, 1))
          .matches(PASSWORD, malformedHash);

      List<ILoggingEvent> events = appender.events();
      assertThat(events).isNotEmpty();
      for (ILoggingEvent event : events) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(PASSWORD)
            .doesNotContain(malformedHash)
            .doesNotContain("aGFzaA");
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      appender.detach();
    }
  }

  @Test
  void 유출_조회_실패_로그에_원문도_해시도_prefix도_남기지_않는다() {
    String sha1 = sha1Upper(PASSWORD);
    ClientHttpRequestFactory failingFactory =
        (uri, httpMethod) -> {
          throw new IOException("I/O error on GET request for " + uri);
        };
    HibpRangePasswordChecker checker =
        new HibpRangePasswordChecker(
            RestClient.builder()
                .baseUrl("https://api.pwnedpasswords.com/range/")
                .requestFactory(failingFactory)
                .build());

    CapturingAppender appender = CapturingAppender.attachTo(HibpRangePasswordChecker.class);
    try {
      assertThat(checker.check(PASSWORD))
          .isEqualTo(CompromisedPasswordChecker.CheckResult.UNAVAILABLE);

      List<ILoggingEvent> events = appender.events();
      assertThat(events).isNotEmpty();
      for (ILoggingEvent event : events) {
        assertThat(event.getFormattedMessage())
            .doesNotContain(PASSWORD)
            .doesNotContain(sha1)
            .doesNotContain(sha1.substring(0, 5))
            .doesNotContain(sha1.substring(5));
        assertThat(event.getThrowableProxy()).isNull();
      }
    } finally {
      appender.detach();
    }
  }
}
