package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.itda.user.application.port.out.CompromisedPasswordCheckResult;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 실제 Pwned Passwords 서버를 호출하지 않는다. 모든 응답은 MockRestServiceServer 또는 실패하는 request factory가 만든다. */
class HibpRangePasswordCheckerTest {

  private static final String BASE_URL = "https://api.pwnedpasswords.com/range/";
  private static final String PASSWORD = "충분히 긴 새 비밀번호 문구";
  private static final String PADDING_LINE = "0".repeat(35) + ":0";

  private MockRestServiceServer server;
  private HibpRangePasswordChecker checker;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    this.server = MockRestServiceServer.bindTo(builder).build();
    this.checker = new HibpRangePasswordChecker(builder.build());
  }

  private static String sha1Upper(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().withUpperCase().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-1 다이제스트를 사용할 수 없습니다.");
    }
  }

  private static String prefix() {
    return sha1Upper(PASSWORD).substring(0, 5);
  }

  private static String suffix() {
    return sha1Upper(PASSWORD).substring(5);
  }

  /** 요청 어디에도 원문·전체 해시·suffix가 실리지 않았는지 확인한다. */
  private static RequestMatcher noSecretLeak() {
    return request -> {
      String uri = request.getURI().toString();
      String headers = request.getHeaders().toString();
      assertThat(uri).isEqualTo(BASE_URL + prefix());
      assertThat(uri + headers)
          .doesNotContain(PASSWORD)
          .doesNotContain(suffix())
          .doesNotContain(sha1Upper(PASSWORD));
    };
  }

  private void expectRangeRequest(String responseBody) {
    this.server
        .expect(requestTo(BASE_URL + prefix()))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Add-Padding", "true"))
        .andExpect(header(HttpHeaders.USER_AGENT, "itda-backend"))
        .andExpect(noSecretLeak())
        .andRespond(withSuccess(responseBody, MediaType.TEXT_PLAIN));
  }

  private static HibpRangePasswordChecker checkerThatFailsWith(IOException failure) {
    ClientHttpRequestFactory failingFactory =
        (uri, httpMethod) -> {
          throw failure;
        };
    return new HibpRangePasswordChecker(
        RestClient.builder().baseUrl(BASE_URL).requestFactory(failingFactory).build());
  }

  @Test
  void 응답에_같은_suffix가_있고_노출_횟수가_1이상이면_COMPROMISED다() {
    expectRangeRequest(suffix() + ":42\r\n" + PADDING_LINE);

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.COMPROMISED);
    this.server.verify();
  }

  @Test
  void 응답에_같은_suffix가_없으면_NOT_COMPROMISED다() {
    expectRangeRequest("1".repeat(35) + ":3\r\n" + "2".repeat(35) + ":7");

    assertThat(this.checker.check(PASSWORD))
        .isEqualTo(CompromisedPasswordCheckResult.NOT_COMPROMISED);
    this.server.verify();
  }

  @Test
  void 노출_횟수가_0인_padding_행은_무시한다() {
    expectRangeRequest(suffix() + ":0\r\n" + PADDING_LINE);

    assertThat(this.checker.check(PASSWORD))
        .isEqualTo(CompromisedPasswordCheckResult.NOT_COMPROMISED);
    this.server.verify();
  }

  @Test
  void 소문자_hex_응답도_같은_기준으로_판정한다() {
    expectRangeRequest(suffix().toLowerCase(Locale.ROOT) + ":5");

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.COMPROMISED);
    this.server.verify();
  }

  @Test
  void 외부에는_대문자_SHA1_앞_5자리_prefix만_보낸다() {
    expectRangeRequest(PADDING_LINE);

    this.checker.check(PASSWORD);

    this.server.verify();
    assertThat(prefix()).hasSize(5).matches("^[0-9A-F]{5}$");
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
  void 오류_응답은_UNAVAILABLE이다(int statusCode) {
    this.server
        .expect(requestTo(BASE_URL + prefix()))
        .andRespond(withStatus(HttpStatus.valueOf(statusCode)));

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void 성공_응답이어도_200이_아니면_UNAVAILABLE이다() {
    this.server
        .expect(requestTo(BASE_URL + prefix()))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void 빈_응답은_UNAVAILABLE이다() {
    expectRangeRequest("");

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "형식이 아닌 본문",
        "AAAA:1",
        "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ:1",
        "11111111111111111111111111111111111:ABC",
        "11111111111111111111111111111111111",
        "11111111111111111111111111111111111:-1",
        "11111111111111111111111111111111111:1:2"
      })
  void 형식이_깨진_응답은_UNAVAILABLE이다(String body) {
    expectRangeRequest(body);

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void 한_줄이라도_깨졌으면_일치하는_suffix가_있어도_UNAVAILABLE이다() {
    expectRangeRequest(suffix() + ":42\r\n깨진 줄");

    assertThat(this.checker.check(PASSWORD)).isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void 연결_실패는_UNAVAILABLE이다() {
    HibpRangePasswordChecker failingChecker =
        checkerThatFailsWith(new IOException("connection refused"));

    assertThat(failingChecker.check(PASSWORD))
        .isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void timeout은_UNAVAILABLE이다() {
    HibpRangePasswordChecker failingChecker =
        checkerThatFailsWith(new SocketTimeoutException("read timed out"));

    assertThat(failingChecker.check(PASSWORD))
        .isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void 전송_실패는_RestClientException_계층으로_들어와_UNAVAILABLE이_된다() {
    // 이 어댑터는 RestClientException만 장애로 변환한다. 아래 단언은 연결·타임아웃 실패가 실제로 그 계층에
    // 속한다는 사실을 고정한다 — 다른 계층이면 check()가 예외를 그대로 던져 이 테스트가 깨진다.
    ClientHttpRequestFactory failingFactory =
        (uri, httpMethod) -> {
          throw new SocketTimeoutException("read timed out");
        };
    RestClient restClient =
        RestClient.builder().baseUrl(BASE_URL).requestFactory(failingFactory).build();

    assertThatThrownBy(() -> restClient.get().uri(prefix()).retrieve().toEntity(String.class))
        .isInstanceOf(RestClientException.class);
    assertThat(new HibpRangePasswordChecker(restClient).check(PASSWORD))
        .isEqualTo(CompromisedPasswordCheckResult.UNAVAILABLE);
  }

  @Test
  void 장애를_NOT_COMPROMISED로_바꾸지_않는다() {
    HibpRangePasswordChecker failingChecker =
        checkerThatFailsWith(new SocketTimeoutException("read timed out"));

    assertThat(failingChecker.check(PASSWORD))
        .isNotEqualTo(CompromisedPasswordCheckResult.NOT_COMPROMISED);
  }
}
