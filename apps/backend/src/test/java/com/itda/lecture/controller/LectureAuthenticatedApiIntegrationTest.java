package com.itda.lecture.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.media.LiveRoomGateway;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;

/**
 * 인증된 사용자로 수업 API를 실제 HTTP로 검증한다. 권한 판정·응답 분기·파라미터 검증은 Controller·Security·오류 처리기가 함께 만들어야 해서 단위
 * 테스트만으로는 드러나지 않는다. OpenVidu 호출은 기록용 대역으로 바꿔 네트워크를 쓰지 않는다.
 */
@WebApiTest
@Import(LectureAuthenticatedApiIntegrationTest.FakeGatewayConfiguration.class)
class LectureAuthenticatedApiIntegrationTest {

  private static final String JSON = "application/json";
  private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private UserRepository userRepository;
  @Autowired private FakeLiveRoomGateway gateway;

  private HttpTestClient client;
  private String instructorToken;
  private String learnerToken;
  private String otherLearnerToken;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.instructorToken = tokenFor(UserRole.INSTRUCTOR);
    this.learnerToken = tokenFor(UserRole.LEARNER);
    this.otherLearnerToken = tokenFor(UserRole.LEARNER);
  }

  /** 매번 새 계정을 만든다. 단조 증가 수열이라 같은 실행 안에서 UNIQUE 제약에 걸리지 않는다. */
  private String tokenFor(UserRole role) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long sequence = ACCOUNT_SEQUENCE.incrementAndGet();
    User user =
        User.create(
            "lecture" + role.name().toLowerCase() + sequence,
            "argon2id$hash",
            role,
            Email.of("lecture" + role.name().toLowerCase() + sequence + "@example.test"),
            now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    return this.accessTokenIssuer
        .issue(new AccessTokenIssuer.Claims(userId, role, 0))
        .compactToken();
  }

  private HttpResponse<String> send(String method, String path, String body, String token) {
    return this.client.send(
        method,
        "/api/v1" + path,
        body,
        "Content-Type",
        JSON,
        HttpHeaders.AUTHORIZATION,
        "Bearer " + token);
  }

  private String createLecture() {
    HttpResponse<String> created =
        send("POST", "/lectures", "{\"title\":\"약자 4회차\"}", this.instructorToken);
    assertThat(created.statusCode()).isEqualTo(201);
    return (String) HttpTestClient.json(created).get("lectureId");
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(HttpTestClient.json(response)).containsEntry("code", code);
  }

  // ---------- 생성·입장·조회 ----------

  @Test
  void 강사가_만든_수업에_학습자가_코드로_입장하고_각자의_Snapshot을_본다() {
    String lectureId = createLecture();

    Map<String, Object> instructorView =
        HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.instructorToken));
    assertThat(instructorView).containsEntry("status", "SCHEDULED");
    String code = (String) instructorView.get("participationCode");
    assertThat(code).matches("\\d{6}");

    String joinBody = "{\"participationCode\":\"" + code + "\"}";
    assertThat(send("POST", "/lecture-participations", joinBody, this.learnerToken).statusCode())
        .isEqualTo(201);
    assertThat(send("POST", "/lecture-participations", joinBody, this.learnerToken).statusCode())
        .isEqualTo(200);

    HttpResponse<String> learnerResponse =
        send("GET", "/lectures/" + lectureId, null, this.learnerToken);
    assertThat(learnerResponse.statusCode()).isEqualTo(200);
    Map<String, Object> learnerView = HttpTestClient.json(learnerResponse);
    assertThat(learnerView).containsEntry("lectureId", lectureId).containsKey("myParticipation");
    assertThat(learnerView).doesNotContainKey("participationCode");
  }

  @Test
  void 참여하지_않은_학습자에게는_수업_존재를_숨긴다() {
    String lectureId = createLecture();

    assertError(
        send("GET", "/lectures/" + lectureId, null, this.otherLearnerToken),
        404,
        "LECTURE_NOT_FOUND");
  }

  @Test
  void 강사가_연_수업의_음성_토큰을_받으면_방을_다시_연다() {
    String lectureId = createLecture();
    assertThat(
            send(
                    "PUT",
                    "/lectures/" + lectureId + "/status",
                    "{\"status\":\"OPEN\"}",
                    this.instructorToken)
                .statusCode())
        .isEqualTo(200);
    this.gateway.openedRooms.clear();

    HttpResponse<String> issued =
        send("POST", "/lectures/" + lectureId + "/media-tokens", null, this.instructorToken);

    assertThat(issued.statusCode()).isEqualTo(201);
    assertThat(this.gateway.openedRooms).contains("lecture-" + lectureId);
  }

  // ---------- 파라미터·본문 검증 ----------

  @Test
  void 음수_page는_400이다() {
    assertError(
        send("GET", "/lectures?page=-1", null, this.instructorToken),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void size가_1보다_작으면_400이다() {
    assertError(
        send("GET", "/lectures?size=0", null, this.instructorToken),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void size가_100을_넘으면_400이다() {
    assertError(
        send("GET", "/lectures?size=101", null, this.instructorToken),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void 양수가_아닌_수업_id는_400이다() {
    assertError(
        send("GET", "/lectures/0", null, this.instructorToken), 400, "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void 빈_title로_수업을_만들면_422다() {
    assertError(
        send("POST", "/lectures", "{\"title\":\" \"}", this.instructorToken),
        422,
        "INVALID_REQUEST_INPUT");
  }

  @Test
  void 형식이_어긋난_참여_코드는_422다() {
    assertError(
        send(
            "POST",
            "/lecture-participations",
            "{\"participationCode\":\"12ab\"}",
            this.learnerToken),
        422,
        "INVALID_REQUEST_INPUT");
  }

  /** 경로 제약과 @Valid body가 함께 있는 메서드는 Spring이 둘을 한 예외로 묶는다 — 오류 처리기는 이를 400으로 돌려준다. */
  @Test
  void 제약_경로와_함께_받은_잘못된_상태_body는_400이다() {
    String lectureId = createLecture();

    assertError(
        send("PUT", "/lectures/" + lectureId + "/status", "{}", this.instructorToken),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  // ---------- 테스트 대역 ----------

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeGatewayConfiguration {

    @Bean
    @Primary
    FakeLiveRoomGateway fakeLiveRoomGateway() {
      return new FakeLiveRoomGateway();
    }
  }

  /** 요청 스레드가 기록하고 테스트 스레드가 읽으므로 동시성 안전한 목록을 쓴다. */
  static class FakeLiveRoomGateway implements LiveRoomGateway {

    final List<String> openedRooms = new CopyOnWriteArrayList<>();
    final List<String> deletedRooms = new CopyOnWriteArrayList<>();

    /** {@code "{room}/{identity}/{allowed}"}. 같은 context를 여러 테스트가 공유하므로 고유한 수업 ID로 찾는다. */
    final List<String> speakingChanges = new CopyOnWriteArrayList<>();

    @Override
    public IssuedMediaToken issueToken(MediaTokenRequest request) {
      return new IssuedMediaToken(
          "wss://test", "token-" + request.identity(), Instant.parse("2026-09-26T01:10:00Z"));
    }

    @Override
    public void openRoom(String roomName) {
      this.openedRooms.add(roomName);
    }

    @Override
    public void deleteRoom(String roomName) {
      this.deletedRooms.add(roomName);
    }

    @Override
    public boolean setSpeaking(String roomName, String identity, boolean allowed) {
      this.speakingChanges.add(roomName + "/" + identity + "/" + allowed);
      return true;
    }
  }
}
