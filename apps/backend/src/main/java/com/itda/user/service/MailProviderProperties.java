package com.itda.user.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SMTP 제공자 연결 설정.
 *
 * <p><b>발송이 켜진 환경에서만 바인딩된다</b> ({@link MailDispatchConfiguration}). 꺼진 환경은 host·자격증명·발신 주소가 없어도
 * 기동해야 하므로, 이 record의 검증은 켜졌을 때만 실행된다.
 *
 * <p>보안 방식은 셋이다. {@link Security#SSL}은 연결부터 TLS(implicit TLS, 흔히 465), {@link Security#STARTTLS}는
 * 평문으로 연결한 뒤 STARTTLS로 반드시 승격한다(흔히 587). 승격에 실패하면 평문으로 계속 보내지 않는다. {@link Security#PLAINTEXT}는 격리된
 * 테스트 환경의 로컬 테스트 서버 전용이며 조립 단계에서 실행 환경과 host를 추가로 검사한다. 포트는 관례값에 고정하지 않는다 — 제공자와 방식이 정하는 값이다.
 *
 * <p>인증은 계정·비밀번호(SMTP AUTH)뿐이다. OAuth·HTTP API는 다루지 않는다. 자격증명은 {@code toString()}에 나오지 않고 검증 실패
 * message에도 값이 들어가지 않는다.
 *
 * <p>발신 주소에는 기본값을 두지 않는다. 제공자가 허용하는 발신 주소는 운영 계정에 묶여 있어 소스의 기본값이 맞을 수 없다. 표시 이름의 서비스 기본값은
 * application.yml의 {@code 이음(I:EUM)}이며, 주소와 별도로 설정할 수 있다.
 *
 * <p>connect·read·write 제한 시간은 소켓 단계별 제한이다. 한 번의 제공자 호출 전체를 제한하는 값은 {@link
 * MailDispatchProperties#providerTimeout()} 하나이며, 여기의 셋은 그것을 대체하지 않는다 — 단계별 제한의 합은 전체 제한보다 클 수 있고,
 * DNS 조회처럼 어느 소켓 제한에도 걸리지 않는 구간이 있다.
 */
@ConfigurationProperties("itda.mail.provider")
public record MailProviderProperties(
    String host,
    int port,
    Security security,
    String username,
    String password,
    String fromAddress,
    String fromName,
    String replyTo,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("30s") Duration writeTimeout) {

  private static final String PREFIX = "itda.mail.provider.";

  public MailProviderProperties {
    // 선택값은 빈 문자열을 "없음"으로 본다. application.yml이 ${ENV:}처럼 비워 두면 null이 아니라 ""가 바인딩된다.
    // 비어 있지 않은 값은 trim하지 않는다. 특히 비밀번호의 앞뒤 공백도 인증 값의 일부다.
    username = blankToNull(username);
    password = blankToNull(password);
    fromName = blankToNull(fromName);
    replyTo = blankToNull(replyTo);
    requireResolved(host, "host");
    requireResolved(username, "username");
    requireResolved(password, "password");
    requireResolved(fromAddress, "from-address");
    requireResolved(fromName, "from-name");
    requireResolved(replyTo, "reply-to");
    requireHost(host);
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(PREFIX + "port는 1 이상 65535 이하여야 합니다.");
    }
    if (security == null) {
      throw new IllegalArgumentException(PREFIX + "security는 SSL, STARTTLS, PLAINTEXT 중 하나여야 합니다.");
    }
    boolean hasUsername = username != null;
    boolean hasPassword = password != null;
    if (security != Security.PLAINTEXT || hasUsername || hasPassword) {
      // TLS 방식은 자격증명이 필수다. PLAINTEXT는 생략할 수 있지만 하나만 주는 것은 설정 실수다.
      if (!hasUsername) {
        throw new IllegalArgumentException(PREFIX + "username이 없습니다.");
      }
      if (!hasPassword) {
        throw new IllegalArgumentException(PREFIX + "password가 없습니다.");
      }
    }
    requireSingleAddress(fromAddress, "from-address", true);
    requireHeaderText(fromName, "from-name");
    requireSingleAddress(replyTo, "reply-to", false);
    requireSocketTimeout(connectTimeout, "connect-timeout");
    requireSocketTimeout(readTimeout, "read-timeout");
    requireSocketTimeout(writeTimeout, "write-timeout");
  }

  /** SMTP AUTH를 시도할지. {@link Security#PLAINTEXT}에서 자격증명을 생략한 경우에만 {@code false}다. */
  public boolean hasCredentials() {
    return this.username != null;
  }

  /** 자격증명·주소는 출력하지 않는다. 연결 대상만 남긴다. */
  @Override
  public String toString() {
    return "MailProviderProperties[host="
        + this.host
        + ", port="
        + this.port
        + ", security="
        + this.security
        + ", REDACTED]";
  }

  /** 연결 방식. */
  public enum Security {
    /** 연결 시작부터 TLS (implicit TLS). */
    SSL,
    /** 평문 연결 뒤 STARTTLS 승격 필수. 승격 실패 시 평문 발송하지 않는다. */
    STARTTLS,
    /** 평문·인증 없음 허용. 격리된 테스트 환경의 루프백 테스트 서버 전용. */
    PLAINTEXT
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * 해석되지 않은 {@code ${...}} 플레이스홀더를 막는다. Spring은 미해결 플레이스홀더에 예외를 던지지 않고 리터럴을 그대로 넘기므로, 환경변수를 빠뜨리면 그
   * 문자열이 host나 비밀번호로 쓰인다 — 실패가 DNS 오류·인증 거부로 나타나 설정 누락임을 알려 주지 않는다.
   */
  private static void requireResolved(String value, String name) {
    if (value != null && value.contains("${")) {
      throw new IllegalArgumentException(PREFIX + name + "의 환경변수 플레이스홀더가 해석되지 않았습니다.");
    }
  }

  private static void requireHost(String host) {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException(PREFIX + "host가 없습니다.");
    }
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (Character.isWhitespace(c) || c == ':' || c == '/' || Character.isISOControl(c)) {
        throw new IllegalArgumentException(PREFIX + "host는 공백·포트·경로 없는 호스트 이름이어야 합니다.");
      }
    }
  }

  /**
   * 표시 이름 없는 단일 주소만 받는다. 표시 이름은 {@code from-name}이 따로 담당하며, 주소 목록이나 헤더 줄바꿈이 섞이면 헤더 주입이 된다.
   *
   * <p>message에 값을 넣지 않는다.
   */
  private static void requireSingleAddress(String value, String name, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new IllegalArgumentException(PREFIX + name + "이(가) 없습니다.");
      }
      return;
    }
    String message = PREFIX + name + "은(는) 표시 이름 없는 단일 이메일 주소여야 합니다.";
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isWhitespace(c)
          || Character.isISOControl(c)
          || c == ','
          || c == '<'
          || c == '>') {
        throw new IllegalArgumentException(message);
      }
    }
    try {
      InternetAddress address = new InternetAddress(value, true);
      address.validate();
      if (address.getPersonal() != null || address.getAddress().indexOf('@') <= 0) {
        throw new IllegalArgumentException(message);
      }
    } catch (AddressException ex) {
      throw new IllegalArgumentException(message);
    }
  }

  /** 헤더에 실리는 자유 문자열은 줄바꿈·제어 문자를 막는다. */
  private static void requireHeaderText(String value, String name) {
    if (value == null) {
      return;
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw new IllegalArgumentException(PREFIX + name + "에는 줄바꿈·제어 문자를 넣을 수 없습니다.");
      }
    }
  }

  /**
   * Angus는 소켓 제한 시간을 밀리초 {@code int}로 받는다. 밀리초로 0이 되면 {@code setSoTimeout(0)} — 무제한 — 이 되고, {@code
   * int}를 넘으면 파싱에 실패해 조용히 -1(무제한)이 된다. 둘 다 "제한이 있다"고 믿게 하는 조용한 실패라 기동 단계에서 막는다.
   */
  private static void requireSocketTimeout(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(PREFIX + name + "은(는) 0보다 커야 합니다.");
    }
    long millis;
    try {
      millis = value.toMillis();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          PREFIX + name + "은(는) 1ms 이상 " + Integer.MAX_VALUE + "ms 이하여야 합니다.");
    }
    if (millis < 1 || millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          PREFIX + name + "은(는) 1ms 이상 " + Integer.MAX_VALUE + "ms 이하여야 합니다.");
    }
  }
}
