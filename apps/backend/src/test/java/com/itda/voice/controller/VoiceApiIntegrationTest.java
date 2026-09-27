package com.itda.voice.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.itda.global.error.ErrorCode;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import com.itda.voice.service.RecordingVoiceSynthesisGateway;
import com.itda.voice.service.StubVoiceRateLimiter;
import com.itda.voice.service.VoiceTestClips;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;

/**
 * 음성 합성 대리 호출을 실제 HTTP로 검증한다 (API 명세 12절). 인증·역할·multipart 해석·오류 body는 Security·Tomcat·오류 처리기가 함께
 * 만들어야 해서 단위 테스트로는 드러나지 않는다. Cartesia와 Redis는 대역으로 바꿔 네트워크를 쓰지 않는다 — 실제 Cartesia 요청 모양은
 * CartesiaVoiceGatewayTest, 실제 Redis 카운터는 RedisVoiceRateLimiterRedisTest가 맡는다.
 */
@WebApiTest
@Import(VoiceApiIntegrationTest.FakeVoiceConfiguration.class)
class VoiceApiIntegrationTest {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private static final String BOUNDARY = "itda-voice-boundary";
  private static final String SPEECH_BODY = "{\"voiceId\":\"voice-1\",\"text\":\"안녕하세요\"}";
  private static final int TEN_MB = 10 * 1024 * 1024;
  private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private UserRepository userRepository;
  @Autowired private RecordingVoiceSynthesisGateway gateway;
  @Autowired private StubVoiceRateLimiter rateLimiter;

  private String learnerToken;
  private String instructorToken;

  @BeforeEach
  void setUp() {
    this.gateway.reset();
    this.rateLimiter.reset();
    this.learnerToken = tokenFor(UserRole.LEARNER);
    this.instructorToken = tokenFor(UserRole.INSTRUCTOR);
  }

  /** 매번 새 계정을 만든다. 단조 증가 수열이라 같은 실행 안에서 UNIQUE 제약에 걸리지 않는다. */
  private String tokenFor(UserRole role) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    String username = "voice" + role.name().toLowerCase() + ACCOUNT_SEQUENCE.incrementAndGet();
    User user =
        User.create(username, "argon2id$hash", role, Email.of(username + "@example.test"), now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    return this.accessTokenIssuer
        .issue(new AccessTokenIssuer.Claims(userId, role, 0))
        .compactToken();
  }

  private <T> HttpResponse<T> post(
      String path,
      String contentType,
      byte[] body,
      String token,
      HttpResponse.BodyHandler<T> handler) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/v1" + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    try {
      return CLIENT.send(request.build(), handler);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** clip·name이 null이면 그 파트를 넣지 않는다. */
  private HttpResponse<String> postClip(String token, byte[] clip, String name) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    if (clip != null) {
      body.writeBytes(
          ("--"
                  + BOUNDARY
                  + "\r\nContent-Disposition: form-data; name=\"clip\"; filename=\"voice-sample.wav\""
                  + "\r\nContent-Type: audio/wav\r\n\r\n")
              .getBytes(UTF_8));
      body.writeBytes(clip);
      body.writeBytes("\r\n".getBytes(UTF_8));
    }
    if (name != null) {
      body.writeBytes(
          ("--"
                  + BOUNDARY
                  + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n"
                  + name
                  + "\r\n")
              .getBytes(UTF_8));
    }
    body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(UTF_8));
    return post(
        "/voice/clones",
        "multipart/form-data; boundary=" + BOUNDARY,
        body.toByteArray(),
        token,
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postSpeech(String token, String json) {
    return post(
        "/voice/speech",
        "application/json",
        json.getBytes(UTF_8),
        token,
        HttpResponse.BodyHandlers.ofString());
  }

  /** 정확히 두 필드만 비교하므로 Cartesia 원문·내부 예외 message가 끼어들 수 없다. */
  private static void assertError(HttpResponse<String> response, ErrorCode code) {
    assertThat(response.statusCode()).isEqualTo(code.getStatus().value());
    assertThat(HttpTestClient.json(response))
        .isEqualTo(Map.of("code", code.getCode(), "message", code.getMessage()));
  }

  // ---------- 성공 ----------

  @Test
  void 학습자가_WAV를_올리면_201과_voiceId를_받고_이름이_그대로_전달된다() {
    HttpResponse<String> response =
        postClip(this.learnerToken, VoiceTestClips.wav(2048), "우리 반 목소리");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(HttpTestClient.json(response))
        .isEqualTo(Map.of("voiceId", RecordingVoiceSynthesisGateway.VOICE_ID));
    assertThat(this.gateway.clonedNames).containsExactly("우리 반 목소리");
    assertThat(this.gateway.clonedClipSizes).containsExactly(2048);
  }

  @Test
  void 정확히_10MB인_녹음은_받는다() {
    HttpResponse<String> response = postClip(this.learnerToken, VoiceTestClips.wav(TEN_MB), null);

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(this.gateway.clonedClipSizes).containsExactly(TEN_MB);
  }

  @Test
  void 학습자가_문장을_보내면_MP3_바이트를_audio_mpeg로_받는다() {
    HttpResponse<byte[]> response =
        post(
            "/voice/speech",
            "application/json",
            "{\"voiceId\":\"voice-1\",\"text\":\"  안녕하세요  \"}".getBytes(UTF_8),
            this.learnerToken,
            HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("audio/mpeg");
    assertThat(response.body()).isEqualTo(RecordingVoiceSynthesisGateway.MP3);
    assertThat(this.gateway.spoken).containsExactly("voice-1/안녕하세요");
  }

  // ---------- 인증·역할 ----------

  @Test
  void 토큰이_없으면_두_API_모두_401_UNAUTHORIZED다() {
    HttpResponse<String> clone = postClip(null, VoiceTestClips.wav(64), null);
    HttpResponse<String> speech = postSpeech(null, SPEECH_BODY);

    assertThat(clone.statusCode()).isEqualTo(401);
    assertThat(speech.statusCode()).isEqualTo(401);
    assertThat(HttpTestClient.json(speech)).containsEntry("code", "UNAUTHORIZED");
    assertThat(this.gateway.clonedNames).isEmpty();
    assertThat(this.gateway.spoken).isEmpty();
  }

  @Test
  void 강사는_두_API_모두_403_LEARNER_ACCESS_REQUIRED다() {
    assertError(
        postClip(this.instructorToken, VoiceTestClips.wav(64), null),
        ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertError(postSpeech(this.instructorToken, SPEECH_BODY), ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertThat(this.rateLimiter.acquiredBy).isEmpty();
  }

  // ---------- 입력 ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"voiceId\":\"voice-1\",\"text\":\"   \"}",
        "{\"voiceId\":\"\",\"text\":\"안녕\"}",
        "{\"text\":\"안녕\"}",
        "{\"voiceId\":\"voice-1\"}"
      })
  void 합성_입력이_계약을_어기면_400_INVALID_REQUEST_PARAMETER다(String body) {
    assertError(postSpeech(this.learnerToken, body), ErrorCode.INVALID_REQUEST_PARAMETER);
    assertThat(this.rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void 녹음_파일이_없거나_WAV가_아니면_400_INVALID_REQUEST_PARAMETER다() {
    assertError(postClip(this.learnerToken, null, "이름만"), ErrorCode.INVALID_REQUEST_PARAMETER);
    assertError(
        postClip(this.learnerToken, "not a wav file at all".getBytes(UTF_8), null),
        ErrorCode.INVALID_REQUEST_PARAMETER);
    assertThat(this.gateway.clonedNames).isEmpty();
  }

  @Test
  void multipart가_아닌_복제_요청은_415_UNSUPPORTED_CONTENT_TYPE이다() {
    HttpResponse<String> response =
        post(
            "/voice/clones",
            "application/json",
            "{}".getBytes(UTF_8),
            this.learnerToken,
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(415);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNSUPPORTED_CONTENT_TYPE");
  }

  @Test
  void boundary가_없는_multipart는_500이_아니라_400_INVALID_REQUEST_PARAMETER다() {
    HttpResponse<String> response =
        post(
            "/voice/clones",
            "multipart/form-data",
            "garbage".getBytes(UTF_8),
            this.learnerToken,
            HttpResponse.BodyHandlers.ofString());

    assertError(response, ErrorCode.INVALID_REQUEST_PARAMETER);
  }

  @Test
  void 녹음_파일이_10MB를_넘으면_413을_body_없이_보낸다() {
    HttpResponse<String> response =
        postClip(this.learnerToken, VoiceTestClips.wav(TEN_MB + 1), null);

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).isEmpty();
    assertThat(this.gateway.clonedNames).isEmpty();
  }

  // ---------- 설정·사용량·외부 실패 ----------

  @Test
  void API_키가_없으면_두_API_모두_503_VOICE_SYNTHESIS_UNAVAILABLE이다() {
    this.gateway.available = false;

    assertError(
        postClip(this.learnerToken, VoiceTestClips.wav(64), null),
        ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertError(postSpeech(this.learnerToken, SPEECH_BODY), ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
  }

  @Test
  void 사용량을_넘으면_두_API_모두_429_VOICE_RATE_LIMITED다() {
    this.rateLimiter.allowing = false;

    assertError(
        postClip(this.learnerToken, VoiceTestClips.wav(64), null), ErrorCode.VOICE_RATE_LIMITED);
    assertError(postSpeech(this.learnerToken, SPEECH_BODY), ErrorCode.VOICE_RATE_LIMITED);
    assertThat(this.gateway.clonedNames).isEmpty();
    assertThat(this.gateway.spoken).isEmpty();
  }

  @Test
  void Cartesia가_실패하면_502이고_원문을_응답에_넣지_않는다() {
    this.gateway.failing = true;

    HttpResponse<String> clone = postClip(this.learnerToken, VoiceTestClips.wav(64), null);
    HttpResponse<String> speech = postSpeech(this.learnerToken, SPEECH_BODY);

    assertError(clone, ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertError(speech, ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertThat(clone.body() + speech.body()).doesNotContain("upstream-secret-detail");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeVoiceConfiguration {

    @Bean
    @Primary
    RecordingVoiceSynthesisGateway recordingVoiceSynthesisGateway() {
      return new RecordingVoiceSynthesisGateway();
    }

    @Bean
    @Primary
    StubVoiceRateLimiter stubVoiceRateLimiter() {
      return new StubVoiceRateLimiter();
    }
  }
}
