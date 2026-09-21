package com.itda.user.service;

import com.itda.user.domain.AccountActionPurpose;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계정 메일에 싣는 용도별 프론트 링크 설정 (API 명세 3.4.1·3.4.2절).
 *
 * <p>원문 action token은 <b>fragment</b>로만 싣는다: {@code <base URL>#token=<토큰>}. 계약이 "원문 action token은
 * 백엔드의 GET·query parameter로 받지 않고 POST body로만 전달한다. 이메일 링크는 프론트 화면 또는 앱 deep link·fragment를 열고,
 * 프론트가 토큰을 POST body에 실어 완료 API를 호출한다"고 정했기 때문이다. fragment는 브라우저가 서버로 보내지 않으므로 프론트 서버·프록시 접근 로그에
 * 토큰이 남지 않는다.
 *
 * <p>base URL에 기본값을 두지 않는다. 환경마다 다른 주소이고, 소스에 들어간 기본 주소는 그대로 운영에 올라가 잘못된 곳으로 사용자를 보낸다. 발송이 켜진 환경에서만
 * 바인딩되므로 꺼진 환경은 이 값이 없어도 기동한다.
 *
 * <p>외부 주소는 https만 허용한다. http는 local·test 환경의 루프백 주소(로컬 프론트 개발 서버)에만 허용한다. 환경 검증은 {@link
 * MailDispatchConfiguration}이 제공자 생성 전에 수행한다. 사용자 정보·질의·fragment가 이미 붙은 주소는 거부한다 — fragment는 이 클래스가
 * 붙이는 자리이고, 질의는 토큰이 아니어도 서버 로그로 흘러가는 자리다.
 */
@ConfigurationProperties("itda.mail.link")
public record MailLinkProperties(String emailVerificationUrl, String passwordResetUrl) {

  private static final String PREFIX = "itda.mail.link.";
  private static final String RULE =
      "은(는) 사용자 정보·질의·프래그먼트가 없는 절대 https URL이어야 합니다 (http는 루프백 주소에만 허용).";

  /** http를 허용하는 host. 로컬 프론트 개발 서버뿐이다. */
  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  public MailLinkProperties {
    requireLinkBase(emailVerificationUrl, "email-verification-url");
    requireLinkBase(passwordResetUrl, "password-reset-url");
  }

  /**
   * 용도별 링크를 만든다. 토큰은 {@code application/x-www-form-urlencoded} 규칙으로 인코딩한다 — compact JWT의
   * 문자(base64url과 {@code .})는 그대로 남고, 그 밖의 문자는 fragment 구분자와 섞이지 않게 퍼센트 인코딩된다.
   *
   * <p>토큰 원문이 지나가는 자리이므로 이 메서드는 로그를 남기지 않는다.
   */
  public String linkFor(AccountActionPurpose purpose, String actionToken) {
    if (purpose == null) {
      throw new IllegalArgumentException("발송 용도가 없습니다.");
    }
    if (actionToken == null || actionToken.isBlank()) {
      throw new IllegalArgumentException("계정 작업 토큰이 없습니다.");
    }
    String base =
        switch (purpose) {
          case EMAIL_VERIFICATION -> this.emailVerificationUrl;
          case PASSWORD_RESET -> this.passwordResetUrl;
        };
    return base + "#token=" + URLEncoder.encode(actionToken, StandardCharsets.UTF_8);
  }

  /**
   * 문자열 접두사 검사가 아니라 URI로 파싱해 확인한다 ({@link BreachCheckProperties}와 같은 이유).
   *
   * <p>message에 입력값을 넣지 않는다 — 설정 오류 로그가 주소를 옮겨 적을 이유가 없다.
   */
  private static void requireLinkBase(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(PREFIX + name + "이(가) 없습니다.");
    }
    if (value.contains("${")) {
      // Spring은 미해결 플레이스홀더를 리터럴로 넘긴다. 링크가 "${MAIL_LINK_...}"로 나가는 것을 기동 단계에서 막는다.
      throw new IllegalArgumentException(PREFIX + name + "의 환경변수 플레이스홀더가 해석되지 않았습니다.");
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException(PREFIX + name + RULE);
    }
    String scheme =
        uri.getScheme() == null ? null : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    boolean hasHost = uri.getHost() != null && !uri.getHost().isEmpty();
    boolean schemeAllowed =
        "https".equals(scheme)
            || ("http".equals(scheme) && hasHost && LOOPBACK_HOSTS.contains(uri.getHost()));
    boolean valid =
        uri.isAbsolute()
            && schemeAllowed
            && hasHost
            && uri.getUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null;
    if (!valid) {
      throw new IllegalArgumentException(PREFIX + name + RULE);
    }
  }
}
