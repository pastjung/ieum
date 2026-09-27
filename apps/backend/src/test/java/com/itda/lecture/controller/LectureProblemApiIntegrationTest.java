package com.itda.lecture.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.controller.LectureAuthenticatedApiIntegrationTest.FakeLiveRoomGateway;
import com.itda.lecture.domain.Participant;
import com.itda.lecture.domain.ParticipantId;
import com.itda.lecture.repository.ParticipantRepository;
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
import java.util.UUID;
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
  @Autowired private ParticipantRepository participantRepository;

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

  // ---------- 출제 (API 명세 8.2·8.5.2절) ----------

  private record Live(String lectureId, long problemId) {}

  /** 학습자가 입장하고 강사가 시작한 수업에 낱말 문제 하나를 연결한다. 출제는 하지 않는다. */
  private Live openLectureWithLinkedWord(List<Integer> cells) {
    String lectureId = createLecture();
    join(lectureId, this.learner);
    changeStatus(lectureId, "OPEN");
    Problem word = seedWord(cells);
    assertThat(register(lectureId, word.getTargetText(), this.instructor).statusCode())
        .isEqualTo(201);
    return new Live(lectureId, word.getId());
  }

  private HttpResponse<String> deliver(String lectureId, long problemId, Account who) {
    return send(
        "PUT",
        "/lectures/" + lectureId + "/current-problem",
        "{\"problemId\":\"" + problemId + "\"}",
        who);
  }

  private long metadataWrites(Live live) {
    String entry = "lecture-" + live.lectureId() + "/" + live.problemId();
    return this.gateway.openedMetadata.stream().filter(entry::equals).count();
  }

  @Test
  void 강사가_출제하면_현재_문제가_바뀌고_방_metadata에_실린다() {
    Live live = openLectureWithLinkedWord(List.of(8, 3));

    HttpResponse<String> delivered = deliver(live.lectureId(), live.problemId(), this.instructor);

    assertThat(delivered.statusCode()).isEqualTo(200);
    assertThat(map(HttpTestClient.json(delivered).get("currentProblem")))
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("expectedPattern", List.of(8, 3));
    assertThat(metadataWrites(live)).isEqualTo(1);

    Map<String, Object> instructorView =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.instructor));
    assertThat(map(instructorView.get("currentProblem")))
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("expectedPattern", List.of(8, 3));

    Map<String, Object> learnerView =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.learner));
    Map<String, Object> current = map(learnerView.get("currentProblem"));
    assertThat(current)
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("problemType", "WORD")
        .containsEntry("cellCount", 2)
        .containsEntry("submitted", false)
        .doesNotContainKey("expectedPattern");
    assertThat(learnerView).containsEntry("myResult", null);
  }

  @Test
  void 같은_문제를_다시_출제해도_200이고_metadata를_다시_쓴다() {
    Live live = openLectureWithLinkedWord(List.of(1));

    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode())
        .isEqualTo(200);
    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode())
        .isEqualTo(200);

    assertThat(metadataWrites(live)).isEqualTo(2);
  }

  @Test
  void 연결하지_않은_문제는_출제할_수_없다() {
    Live live = openLectureWithLinkedWord(List.of(1));

    assertError(
        deliver(live.lectureId(), seedWord(List.of(2)).getId(), this.instructor),
        404,
        "PROBLEM_NOT_FOUND");
  }

  @Test
  void 시작_전_수업에서는_출제할_수_없다() {
    String lectureId = createLecture();
    Problem word = seedWord(List.of(1));
    register(lectureId, word.getTargetText(), this.instructor);

    assertError(deliver(lectureId, word.getId(), this.instructor), 409, "INVALID_LECTURE_STATE");
  }

  @Test
  void 담당이_아닌_강사에게는_출제_대상_수업을_숨긴다() {
    Live live = openLectureWithLinkedWord(List.of(1));

    assertError(
        deliver(live.lectureId(), live.problemId(), accountFor(UserRole.INSTRUCTOR)),
        404,
        "LECTURE_NOT_FOUND");
  }

  @Test
  void metadata_쓰기가_실패하면_503이지만_출제는_저장되고_다시_누르면_복구된다() {
    Live live = openLectureWithLinkedWord(List.of(1));
    this.gateway.failRoomOperations = true;
    try {
      assertError(
          deliver(live.lectureId(), live.problemId(), this.instructor),
          503,
          "MEDIA_SERVER_UNAVAILABLE");
    } finally {
      this.gateway.failRoomOperations = false;
    }

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.instructor));
    assertThat(map(view.get("currentProblem")))
        .containsEntry("problemId", String.valueOf(live.problemId()));

    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode())
        .isEqualTo(200);
    assertThat(metadataWrites(live)).isEqualTo(1);
  }

  @Test
  void 강사가_음성_토큰을_받으면_현재_문제를_metadata에_다시_싣는다() {
    Live live = openLectureWithLinkedWord(List.of(1));
    deliver(live.lectureId(), live.problemId(), this.instructor);

    assertThat(
            send("POST", "/lectures/" + live.lectureId() + "/media-tokens", null, this.instructor)
                .statusCode())
        .isEqualTo(201);

    assertThat(metadataWrites(live)).isEqualTo(2);
  }

  // ---------- LIVE 답안 (API 명세 8.2.2절) ----------

  private Live deliveredWord(List<Integer> cells) {
    Live live = openLectureWithLinkedWord(cells);
    assertThat(deliver(live.lectureId(), live.problemId(), this.instructor).statusCode())
        .isEqualTo(200);
    return live;
  }

  private HttpResponse<String> submit(Live live, Account who, String cellsJson) {
    return submitRaw(
        live,
        who,
        "{\"cells\":" + cellsJson + ",\"durationMs\":1900}",
        "Idempotency-Key",
        UUID.randomUUID().toString());
  }

  /** 헤더를 직접 고른다 — 멱등 키 누락·형식 오류 사례용. */
  private HttpResponse<String> submitRaw(Live live, Account who, String body, String... headers) {
    return send(
        "POST",
        "/lectures/" + live.lectureId() + "/problems/" + live.problemId() + "/answers",
        body,
        who,
        headers);
  }

  private long signals(Live live) {
    String entry =
        "lecture-"
            + live.lectureId()
            + "/user-"
            + this.instructor.userId()
            + "/"
            + live.problemId();
    return this.gateway.answerSignals.stream().filter(entry::equals).count();
  }

  @Test
  void 학습자가_정답을_내면_201과_채점_결과를_받고_강사에게_알림이_간다() {
    Live live = deliveredWord(List.of(8, 3));

    HttpResponse<String> response = submit(live, this.learner, "[8,3]");

    assertThat(response.statusCode()).isEqualTo(201);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body)
        .containsEntry("lectureId", live.lectureId())
        .containsEntry("problemId", String.valueOf(live.problemId()))
        .containsEntry("isCorrect", true)
        .containsEntry("score", 100)
        .containsKey("attemptId")
        .containsKey("attemptedAt");
    assertThat(list(body.get("cells")))
        .extracting(cell -> map(cell).get("cellIndex"))
        .containsExactly(1, 2);
    assertThat(signals(live)).isEqualTo(1);
  }

  @Test
  void 오답은_칸별_diffMask와_부분_점수를_받는다() {
    Live live = deliveredWord(List.of(8, 3));

    Map<String, Object> body = HttpTestClient.json(submit(live, this.learner, "[8]"));

    assertThat(body).containsEntry("isCorrect", false).containsEntry("score", 50);
    assertThat(map(list(body.get("cells")).get(1)))
        .containsEntry("cellIndex", 2)
        .containsEntry("inputPattern", 0)
        .containsEntry("expectedPattern", 3)
        .containsEntry("diffMask", 3)
        .containsEntry("isCorrect", false);
  }

  @Test
  void 같은_cells를_다시_보내면_200과_기존_결과이고_알림은_다시_가지_않는다() {
    Live live = deliveredWord(List.of(8));
    HttpResponse<String> first = submit(live, this.learner, "[8]");

    HttpResponse<String> again = submit(live, this.learner, "[8]");

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(HttpTestClient.json(again).get("attemptId"))
        .isEqualTo(HttpTestClient.json(first).get("attemptId"));
    assertThat(signals(live)).isEqualTo(1);
  }

  @Test
  void 다른_cells를_다시_보내면_409다() {
    Live live = deliveredWord(List.of(8));
    submit(live, this.learner, "[8]");

    assertError(submit(live, this.learner, "[9]"), 409, "LECTURE_PROBLEM_ALREADY_ANSWERED");
  }

  @Test
  void 제출하면_학습자_Snapshot에_submitted와_myResult가_담긴다() {
    Live live = deliveredWord(List.of(8));
    Object attemptId = HttpTestClient.json(submit(live, this.learner, "[9]")).get("attemptId");

    Map<String, Object> view =
        HttpTestClient.json(send("GET", "/lectures/" + live.lectureId(), null, this.learner));

    assertThat(map(view.get("currentProblem"))).containsEntry("submitted", true);
    assertThat(map(view.get("myResult")))
        .containsEntry("attemptId", attemptId)
        .containsEntry("isCorrect", false);
  }

  @Test
  void 현재_문제가_아니면_409다() {
    Live live = deliveredWord(List.of(8));
    Problem next = seedWord(List.of(1));
    register(live.lectureId(), next.getTargetText(), this.instructor);
    deliver(live.lectureId(), next.getId(), this.instructor);

    assertError(submit(live, this.learner, "[8]"), 409, "LECTURE_PROBLEM_NOT_CURRENT");
  }

  @Test
  void 퇴장한_학습자는_403이다() {
    Live live = deliveredWord(List.of(8));
    Participant participant =
        this.participantRepository
            .findById(ParticipantId.of(Long.parseLong(live.lectureId()), this.learner.userId()))
            .orElseThrow();
    participant.leave(LocalDateTime.now(ZoneOffset.UTC));
    this.participantRepository.save(participant);

    assertError(submit(live, this.learner, "[8]"), 403, "LECTURE_PARTICIPATION_REQUIRED");
  }

  @Test
  void 종료된_수업에는_제출할_수_없다() {
    Live live = deliveredWord(List.of(8));
    changeStatus(live.lectureId(), "CLOSED");

    assertError(submit(live, this.learner, "[8]"), 409, "INVALID_LECTURE_STATE");
  }

  @Test
  void 강사는_답안을_낼_수_없다() {
    Live live = deliveredWord(List.of(8));

    assertError(submit(live, this.instructor, "[8]"), 403, "LEARNER_ACCESS_REQUIRED");
  }

  @Test
  void 참여하지_않은_학습자에게는_수업을_숨긴다() {
    Live live = deliveredWord(List.of(8));

    assertError(submit(live, this.otherLearner, "[8]"), 404, "LECTURE_NOT_FOUND");
  }

  @Test
  void 비었거나_범위를_벗어나거나_12칸을_넘는_cells는_422다() {
    Live live = deliveredWord(List.of(8));

    assertError(submit(live, this.learner, "[]"), 422, "INVALID_ANSWER_CELLS");
    assertError(submit(live, this.learner, "[64]"), 422, "INVALID_ANSWER_CELLS");
    assertError(
        submit(live, this.learner, "[1,1,1,1,1,1,1,1,1,1,1,1,1]"), 422, "INVALID_ANSWER_CELLS");
  }

  @Test
  void Idempotency_Key가_없거나_UUID_v4가_아니면_400이다() {
    Live live = deliveredWord(List.of(8));
    String body = "{\"cells\":[8],\"durationMs\":0}";

    assertError(submitRaw(live, this.learner, body), 400, "INVALID_REQUEST_PARAMETER");
    assertError(
        submitRaw(live, this.learner, body, "Idempotency-Key", "not-a-uuid"),
        400,
        "INVALID_REQUEST_PARAMETER");
    assertError(
        // RFC 4122 예시 UUID(v3)다. gitleaks가 고엔트로피 문자열로 오탐하므로 줄 단위로 눈감긴다(비밀값 아님).
        submitRaw(
            live,
            this.learner,
            body,
            "Idempotency-Key",
            "123e4567-e89b-12d3-a456-426614174000"), // gitleaks:allow
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void durationMs가_없거나_음수면_400이다() {
    Live live = deliveredWord(List.of(8));

    assertError(
        submitRaw(
            live,
            this.learner,
            "{\"cells\":[8],\"durationMs\":-1}",
            "Idempotency-Key",
            UUID.randomUUID().toString()),
        400,
        "INVALID_REQUEST_PARAMETER");
    assertError(
        submitRaw(
            live, this.learner, "{\"cells\":[8]}", "Idempotency-Key", UUID.randomUUID().toString()),
        400,
        "INVALID_REQUEST_PARAMETER");
  }

  @Test
  void 알림이_실패해도_제출은_201이다() {
    Live live = deliveredWord(List.of(8));
    this.gateway.failSignals = true;
    try {
      assertThat(submit(live, this.learner, "[8]").statusCode()).isEqualTo(201);
    } finally {
      this.gateway.failSignals = false;
    }
  }

  // ---------- 강사 답안 목록 (API 명세 8.2.2절) ----------

  private HttpResponse<String> answers(String lectureId, long problemId, Account who) {
    return send("GET", "/lectures/" + lectureId + "/problems/" + problemId + "/answers", null, who);
  }

  @Test
  void 강사는_제출한_학습자의_답안만_제출_순서대로_본다() {
    Live live = openLectureWithLinkedWord(List.of(8, 3));
    join(live.lectureId(), this.otherLearner);
    Account absent = accountFor(UserRole.LEARNER);
    join(live.lectureId(), absent);
    deliver(live.lectureId(), live.problemId(), this.instructor);
    submit(live, this.learner, "[9,3]");
    submit(live, this.otherLearner, "[8,3]");

    HttpResponse<String> response = answers(live.lectureId(), live.problemId(), this.instructor);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = HttpTestClient.json(response);
    assertThat(body).containsEntry("problemId", String.valueOf(live.problemId()));
    List<Object> items = list(body.get("answers"));
    assertThat(items).hasSize(2);
    assertThat(map(items.get(0)))
        .containsEntry("userId", String.valueOf(this.learner.userId()))
        .containsEntry("username", this.learner.username())
        .containsEntry("isCorrect", false)
        .containsKey("attemptedAt");
    assertThat(map(list(map(items.get(0)).get("cells")).get(0))).containsEntry("diffMask", 1);
    assertThat(map(items.get(1)))
        .containsEntry("userId", String.valueOf(this.otherLearner.userId()))
        .containsEntry("isCorrect", true);
  }

  @Test
  void 제출이_없으면_빈_목록이다() {
    Live live = deliveredWord(List.of(8));

    assertThat(
            list(
                HttpTestClient.json(answers(live.lectureId(), live.problemId(), this.instructor))
                    .get("answers")))
        .isEmpty();
  }

  @Test
  void 연결되지_않은_문제의_답안_목록은_404다() {
    Live live = deliveredWord(List.of(8));

    assertError(
        answers(live.lectureId(), seedWord(List.of(1)).getId(), this.instructor),
        404,
        "PROBLEM_NOT_FOUND");
  }

  @Test
  void 학습자에게는_답안_목록_대상_수업을_숨긴다() {
    Live live = deliveredWord(List.of(8));

    assertError(
        answers(live.lectureId(), live.problemId(), this.learner), 404, "LECTURE_NOT_FOUND");
  }

  @Test
  void 담당이_아닌_강사에게는_답안_목록_대상_수업을_숨긴다() {
    Live live = deliveredWord(List.of(8));

    assertError(
        answers(live.lectureId(), live.problemId(), accountFor(UserRole.INSTRUCTOR)),
        404,
        "LECTURE_NOT_FOUND");
  }

  @Test
  void 종료_뒤에도_답안_목록을_본다() {
    Live live = deliveredWord(List.of(8));
    submit(live, this.learner, "[8]");
    changeStatus(live.lectureId(), "CLOSED");

    HttpResponse<String> response = answers(live.lectureId(), live.problemId(), this.instructor);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(list(HttpTestClient.json(response).get("answers"))).hasSize(1);
  }

  // ---------- 수업 목록 항목의 집계 (API 명세 8.2절) ----------

  private HttpResponse<String> listLectures(Account who) {
    return send("GET", "/lectures", null, who);
  }

  private void leave(String lectureId, Account who) {
    Participant participant =
        this.participantRepository
            .findById(ParticipantId.of(Long.parseLong(lectureId), who.userId()))
            .orElseThrow();
    participant.leave(LocalDateTime.now(ZoneOffset.UTC));
    this.participantRepository.save(participant);
  }

  private Map<String, Object> listItem(String lectureId) {
    return list(HttpTestClient.json(listLectures(this.instructor)).get("items")).stream()
        .map(LectureProblemApiIntegrationTest::map)
        .filter(item -> lectureId.equals(item.get("lectureId")))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void 목록_항목은_연결_문제_참여자_LIVE_정답률을_담는다() {
    Live live = openLectureWithLinkedWord(List.of(8, 3));
    Problem second = seedWord(List.of(1));
    register(live.lectureId(), second.getTargetText(), this.instructor);
    join(live.lectureId(), this.otherLearner);
    deliver(live.lectureId(), live.problemId(), this.instructor);
    submit(live, this.learner, "[8,3]");
    submit(live, this.otherLearner, "[9,3]");
    leave(live.lectureId(), this.otherLearner);

    Map<String, Object> item = listItem(live.lectureId());

    assertThat(item)
        .containsEntry("problemCount", 2)
        .containsEntry("joinedCount", 1)
        .containsEntry("participantCount", 2)
        .containsEntry("correctRate", 50)
        .containsEntry("currentProblem", null)
        .containsEntry("linkedProblems", List.of());
  }

  @Test
  void LIVE_풀이가_없는_수업의_목록_항목은_correctRate가_null이다() {
    String lectureId = createLecture();

    Map<String, Object> item = listItem(lectureId);

    assertThat(item)
        .containsEntry("problemCount", 0)
        .containsEntry("joinedCount", 0)
        .containsEntry("participantCount", 0)
        .containsEntry("correctRate", null);
  }
}
