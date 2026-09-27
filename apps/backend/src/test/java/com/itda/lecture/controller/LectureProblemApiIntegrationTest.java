package com.itda.lecture.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.controller.LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway;
import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.domain.ProblemSkill;
import com.itda.problem.domain.SkillType;
import com.itda.problem.repository.LearningSkillRepository;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import com.itda.problem.repository.ProblemSkillRepository;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

/**
 * 수업 문제 연결·출제·LIVE 답안을 실제 HTTP와 H2로 검증한다 (API 명세 8.2·8.5.2절). OpenVidu 대역은 {@link
 * LectureAuthenticatedApiIntegrationTest}의 구성을 그대로 가져와 애플리케이션 context를 공유한다.
 */
@WebApiTest
@Import(LectureAuthenticatedApiIntegrationTest.FakeGatewayConfiguration.class)
class LectureProblemApiIntegrationTest {

  private static final String JSON = "application/json";

  /** 같은 DB를 다른 테스트 클래스와 나눠 쓰므로 username·낱말에 이 클래스만의 접두어와 수열을 쓴다. */
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private UserRepository userRepository;
  @Autowired private FakeLiveRoomGateway gateway;
  @Autowired private ProblemRepository problemRepository;
  @Autowired private ProblemCellRepository problemCellRepository;
  @Autowired private LearningSkillRepository learningSkillRepository;
  @Autowired private ProblemSkillRepository problemSkillRepository;

  private HttpTestClient client;
  private Account instructor;
  private Account learner;
  private Account otherLearner;

  @BeforeEach
  void setUp() {
    this.client = new HttpTestClient(this.port);
    this.instructor = accountFor(UserRole.INSTRUCTOR);
    this.learner = accountFor(UserRole.LEARNER);
    this.otherLearner = accountFor(UserRole.LEARNER);
  }

  // ---------- 도우미 ----------

  private record Account(long userId, String username, String token) {}

  private Account accountFor(UserRole role) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    String username = "lp" + role.name().toLowerCase() + SEQUENCE.incrementAndGet();
    User user =
        User.create(username, "argon2id$hash", role, Email.of(username + "@example.test"), now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    String token =
        this.accessTokenIssuer.issue(new AccessTokenIssuer.Claims(userId, role, 0)).compactToken();
    return new Account(userId, username, token);
  }

  private HttpResponse<String> send(
      String method, String path, String body, Account who, String... extraHeaders) {
    String[] headers = new String[4 + extraHeaders.length];
    headers[0] = "Content-Type";
    headers[1] = JSON;
    headers[2] = HttpHeaders.AUTHORIZATION;
    headers[3] = "Bearer " + who.token();
    System.arraycopy(extraHeaders, 0, headers, 4, extraHeaders.length);
    return this.client.send(method, "/api/v1" + path, body, headers);
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(HttpTestClient.json(response)).containsEntry("code", code);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private String createLecture() {
    HttpResponse<String> created =
        send("POST", "/lectures", "{\"title\":\"문제 출제 수업\"}", this.instructor);
    assertThat(created.statusCode()).isEqualTo(201);
    return (String) HttpTestClient.json(created).get("lectureId");
  }

  private void join(String lectureId, Account who) {
    String code =
        (String)
            HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.instructor))
                .get("participationCode");
    int status =
        send("POST", "/lecture-participations", "{\"participationCode\":\"" + code + "\"}", who)
            .statusCode();
    assertThat(status).isIn(200, 201);
  }

  private void changeStatus(String lectureId, String status) {
    assertThat(
            send(
                    "PUT",
                    "/lectures/" + lectureId + "/status",
                    "{\"status\":\"" + status + "\"}",
                    this.instructor)
                .statusCode())
        .isEqualTo(200);
  }

  /** AI를 부르지 않도록 낱말 문제를 미리 만든다. 글자는 테스트마다 다르다. */
  private Problem seedWord(List<Integer> cells) {
    Problem problem =
        this.problemRepository.save(new Problem(Level.WORD, "낱말" + SEQUENCE.incrementAndGet()));
    for (int i = 0; i < cells.size(); i++) {
      this.problemCellRepository.save(new ProblemCell(problem, i + 1, cells.get(i)));
    }
    return problem;
  }

  private LearningSkill skill(SkillType type, String metadata) {
    return this.learningSkillRepository
        .findBySkillTypeAndMetadata(type, metadata)
        .orElseGet(
            () ->
                this.learningSkillRepository.save(
                    LearningSkill.of(type, metadata, "테스트 요소", null)));
  }

  private Problem seedConsonant(String text, SkillType type, int pattern) {
    Problem problem = this.problemRepository.save(new Problem(Level.CONSONANT, text));
    this.problemCellRepository.save(new ProblemCell(problem, 1, pattern));
    this.problemSkillRepository.save(new ProblemSkill(problem, skill(type, text)));
    return problem;
  }

  private HttpResponse<String> register(String lectureId, String targetText, Account who) {
    return send(
        "POST",
        "/lectures/" + lectureId + "/problems",
        "{\"targetText\":\"" + targetText + "\"}",
        who);
  }

  // ---------- 문제 연결 (API 명세 8.2.1절) ----------

  @Test
  void 강사가_낱말을_연결하면_201과_정답_점형을_받고_다시_연결하면_200이다() {
    String lectureId = createLecture();
    Problem word = seedWord(List.of(8, 3));

    HttpResponse<String> first = register(lectureId, word.getTargetText(), this.instructor);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(HttpTestClient.json(first))
        .containsEntry("problemId", String.valueOf(word.getId()))
        .containsEntry("targetText", word.getTargetText())
        .containsEntry("displayText", word.getTargetText())
        .containsEntry("problemType", "WORD")
        .containsEntry("cellCount", 2)
        .containsEntry("expectedPattern", List.of(8, 3));

    HttpResponse<String> again = register(lectureId, word.getTargetText(), this.instructor);

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(again)).containsEntry("problemId", String.valueOf(word.getId()));
  }

  @Test
  void 구분어_없는_낱자에_후보가_둘이면_409와_후보를_받고_후보로_다시_연결한다() {
    // 'ㅎ'은 이 테스트만 만든다 — 다른 테스트가 같은 글자를 쓰면 후보가 늘어난다.
    Problem onset = seedConsonant("ㅎ", SkillType.SKILL_01, 26);
    seedConsonant("ㅎ", SkillType.SKILL_03, 22);
    String lectureId = createLecture();

    HttpResponse<String> ambiguous = register(lectureId, "ㅎ", this.instructor);

    assertError(ambiguous, 409, "PROBLEM_TEXT_AMBIGUOUS");
    assertThat(list(HttpTestClient.json(ambiguous).get("candidates")))
        .containsExactly(
            Map.of("input", "초성 ㅎ", "displayText", "초성 ㅎ"),
            Map.of("input", "받침 ㅎ", "displayText", "받침 ㅎ"));

    HttpResponse<String> chosen = register(lectureId, "초성 ㅎ", this.instructor);

    assertThat(chosen.statusCode()).isEqualTo(201);
    assertThat(HttpTestClient.json(chosen))
        .containsEntry("problemId", String.valueOf(onset.getId()))
        .containsEntry("displayText", "초성 ㅎ")
        .containsEntry("problemType", "CONSONANT");
  }

  @Test
  void 구분어가_붙었는데_문제가_없으면_404다() {
    assertError(register(createLecture(), "음절 뷁", this.instructor), 404, "PROBLEM_NOT_FOUND");
  }

  @Test
  void 종료된_수업에는_문제를_연결할_수_없다() {
    String lectureId = createLecture();
    changeStatus(lectureId, "OPEN");
    changeStatus(lectureId, "CLOSED");

    assertError(
        register(lectureId, seedWord(List.of(1)).getTargetText(), this.instructor),
        409,
        "INVALID_LECTURE_STATE");
  }

  @Test
  void 학습자는_문제를_연결할_수_없다() {
    assertError(register(createLecture(), "가", this.learner), 403, "INSTRUCTOR_ACCESS_REQUIRED");
  }

  @Test
  void 담당이_아닌_강사에게는_수업을_숨긴다() {
    assertError(
        register(createLecture(), "가", accountFor(UserRole.INSTRUCTOR)), 404, "LECTURE_NOT_FOUND");
  }

  @Test
  void 빈_targetText는_400이다() {
    assertError(register(createLecture(), " ", this.instructor), 400, "INVALID_REQUEST_PARAMETER");
  }

  // ---------- Snapshot (API 명세 8.2절) ----------

  @Test
  void 강사_Snapshot은_연결_순서대로_문제를_담고_출제_전_현재_문제는_null이다() {
    String lectureId = createLecture();
    Problem first = seedWord(List.of(1));
    Problem second = seedWord(List.of(2, 4));
    register(lectureId, first.getTargetText(), this.instructor);
    register(lectureId, second.getTargetText(), this.instructor);

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.instructor));

    assertThat(view.get("currentProblem")).isNull();
    assertThat(list(view.get("linkedProblems")))
        .extracting(problem -> map(problem).get("problemId"))
        .containsExactly(String.valueOf(first.getId()), String.valueOf(second.getId()));
    assertThat(map(list(view.get("linkedProblems")).get(1)))
        .containsEntry("expectedPattern", List.of(2, 4));
  }

  @Test
  void 학습자_Snapshot은_출제_전_현재_문제와_결과가_null이다() {
    String lectureId = createLecture();
    join(lectureId, this.learner);

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + lectureId, null, this.learner));

    assertThat(view).containsEntry("currentProblem", null).containsEntry("myResult", null);
  }
}
