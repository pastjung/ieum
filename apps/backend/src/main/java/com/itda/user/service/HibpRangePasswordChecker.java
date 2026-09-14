package com.itda.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pwned Passwords range API 어댑터. 흔한 값과 유출 값을 가르는 단일 blocklist로 사용한다.
 *
 * <p><b>외부로 나가는 정보.</b> 비밀번호 SHA-1의 앞 5자리 prefix뿐이다(k-anonymity). 전체 해시와 35자리 suffix 비교는 응답을 받은 뒤
 * 로컬에서만 한다. 원문과 전체 해시를 보내지 않는다는 것이 API 명세 3.6.2절의 요구사항이다.
 *
 * <p><b>fail-closed.</b> 연결 실패·타임아웃·200이 아닌 응답·빈 응답·형식이 깨진 응답은 모두 {@code UNAVAILABLE}이다. 이 값을
 * {@code NOT_COMPROMISED}로 접으면 제공자 장애 중에 유출된 비밀번호가 통과한다. 재시도와 캐시는 두지 않는다 — 재시도는 동기 요청의 지연을 배로 늘리고,
 * 캐시는 비밀번호 해시 prefix를 서버 메모리에 남긴다.
 *
 * <p><b>로그.</b> 실패 로그에 prefix도 예외 message도 남기지 않는다. 클라이언트 예외 message에는 요청 URL이 들어 있어 그대로 기록하면
 * prefix가 로그로 새어 나간다. 고정된 provider·단계 분류만 남긴다.
 */
public class HibpRangePasswordChecker implements CompromisedPasswordChecker {

  private static final String PROVIDER = "hibp-range";
  private static final int PREFIX_LENGTH = 5;

  /** 응답 한 줄: 35자리 SHA-1 suffix와 노출 횟수. */
  private static final Pattern LINE_PATTERN = Pattern.compile("^([0-9A-F]{35}):(\\d{1,12})$");

  private static final Logger log = LoggerFactory.getLogger(HibpRangePasswordChecker.class);

  private final RestClient restClient;

  public HibpRangePasswordChecker(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public CompromisedPasswordChecker.CheckResult check(String normalizedPassword) {
    String sha1 = sha1UpperHex(normalizedPassword);
    String prefix = sha1.substring(0, PREFIX_LENGTH);
    String suffix = sha1.substring(PREFIX_LENGTH);

    ResponseEntity<String> response;
    try {
      response =
          this.restClient
              .get()
              .uri(prefix)
              // 응답 크기로 어떤 prefix를 물었는지 추정하지 못하게 한다. 패딩 행의 count는 0이다.
              .header("Add-Padding", "true")
              .header(HttpHeaders.USER_AGENT, "itda-backend")
              .retrieve()
              .toEntity(String.class);
    } catch (RestClientException ex) {
      // 연결 실패·타임아웃·4xx·5xx·응답 읽기 실패가 모두 이 계층에 속한다. 더 넓게 잡으면 우리 쪽 프로그래밍 오류까지
      // 제공자 장애로 숨겨져 fail-closed 뒤에 버그가 조용히 묻힌다.
      return unavailable("request");
    }

    if (response.getStatusCode() != HttpStatus.OK) {
      return unavailable("response-status");
    }
    return evaluate(response.getBody(), suffix);
  }

  /** 응답 전체가 형식에 맞을 때만 판정한다. 한 줄이라도 깨졌으면 결과를 신뢰할 수 없다. */
  private CompromisedPasswordChecker.CheckResult evaluate(String body, String suffix) {
    if (body == null || body.isBlank()) {
      return unavailable("empty-response");
    }
    boolean compromised = false;
    for (String line : body.lines().toList()) {
      Matcher matcher = LINE_PATTERN.matcher(line.toUpperCase(Locale.ROOT));
      if (!matcher.matches()) {
        return unavailable("response-format");
      }
      if (matcher.group(1).equals(suffix) && Long.parseLong(matcher.group(2)) > 0) {
        compromised = true;
      }
    }
    return compromised
        ? CompromisedPasswordChecker.CheckResult.COMPROMISED
        : CompromisedPasswordChecker.CheckResult.NOT_COMPROMISED;
  }

  private static CompromisedPasswordChecker.CheckResult unavailable(String stage) {
    log.warn("유출 비밀번호 조회에 실패했습니다. provider={} stage={}", PROVIDER, stage);
    return CompromisedPasswordChecker.CheckResult.UNAVAILABLE;
  }

  private static String sha1UpperHex(String normalizedPassword) {
    byte[] digest = sha1Digest().digest(normalizedPassword.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().withUpperCase().formatHex(digest);
  }

  private static MessageDigest sha1Digest() {
    try {
      return MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-1 다이제스트를 사용할 수 없습니다.");
    }
  }
}
