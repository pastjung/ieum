# 학습 세션 API 연동 설계

작성일: 2026-09-24
대상: `apps/backend`, `front/apps/mobile`, `front/packages/contracts`

학습자 앱의 자기주도 학습 한 바퀴(학습 시작 → 문항 풀이 → 제출 → 결과)를 실제 백엔드 API에
연결한다. 지금은 전 구간이 목 데이터다.

## 1. 연동 대상

| 엔드포인트 | 쓰임 |
|---|---|
| `POST /api/v1/learning-sessions` | 세션 생성. `{curriculumId}` → `{sessionId, problems[]}` |
| `GET /api/v1/learning-sessions/{sessionId}` | 인증 보호만 한다 — 앱은 이번에 호출하지 않는다 (9절) |
| `POST /api/v1/learning-sessions/{sessionId}/completion` | 일괄 제출·최종 채점 |

`POST /api/v1/lectures/{lectureId}/problems` 는 제외한다. `validateInstructor()` 로 막힌 교육자
전용 API이고, 학습자에게 문제를 내려보내는 절반이 `ProblemService.java:67` 에
`// TODO:수강생들에게 문제 전달` 로 비어 있다. 학습자 앱이 부를 API가 아니다.

`POST/GET /api/v1/review-sessions` 도 제외한다. 화면을 재사용하므로 나중에 붙이는 비용이 작다.

## 2. 착수 전에 확인한 사실

연동을 막고 있던 것들이다. 설계의 근거가 되므로 남긴다.

### 2.1 세 경로가 인증되지 않는다

`SecurityConfig.java:92` 의 보호 체인이 매칭하는 경로는 `GET /users/me` 와
`POST /users/me/withdrawals` 둘뿐이다. 나머지는 전부 `permitAll` 체인으로 간다.

```
POST /api/v1/learning-sessions  (토큰 없음)
→ 500 InvalidDataAccessApiUsageException: The given id must not be null
```

401이 아니라 500이다. 토큰 없이 서비스 계층까지 들어가고 `@AuthenticationPrincipal` 이 null이라
거기서 터진다. 앱이 "누구의 세션인지" 말할 방법이 없다.

### 2.2 완료 엔드포인트가 `/api/v1/api/v1/...` 에 있다

`application.yml:35` 가 `context-path: /api/v1` 을 붙이는데
`LearningSessionCompletionController` 가 클래스에 `@RequestMapping("/api/v1/learning-sessions")`
를 또 붙였다. 실측:

| 경로 | 응답 |
|---|---|
| `POST /api/v1/learning-sessions/1/completion` | 404 |
| `POST /api/v1/api/v1/learning-sessions/1/completion` | 422 |

같은 저장소의 `ProblemController` 는 프리픽스를 붙이지 않는다. 두 컨트롤러의 규칙이 반대다.

### 2.3 두 엔드포인트가 맞물리지 않는다

세션 생성이 돌려주는 문항에 `problemSessionId` 가 없다.

```java
ProblemDetail(problemId, sequence, targetText, expectedPatterns, completed)
```

완료 제출은 문항마다 그것을 요구한다.

```java
AttemptRequest(problemSessionId, inputPatterns)
```

`ProblemService.java:177` 이 `ProblemSession` 을 저장하지만 ID를 응답에 넣지 않는다. 받은 값만으로
제출 요청을 만들 수 없다.

`problemSessionId` 는 문항 배정의 식별자이고 완료 제출이 이것을 요구한다. `problem_sessions` 에
`(problem_id, session_id)` 유니크 제약이 있어 한 세션 안에서는 문제 하나에 배정 하나가 대응하지만,
**그 대응을 아는 것은 서버뿐이다.** 앱은 `problemId` 로부터 `problemSessionId` 를 유도할 방법이
없으므로 서버가 응답에 실어 보내야 한다.

이 셋을 고치지 않으면 어떤 형태의 연동도 불가능하다.

### 2.4 구현이 API 명세와 다르다

| 명세 (`docs/product/api-spec.md`) | 구현 |
|---|---|
| `POST /learning-sessions` body `curriculumStageId` | `curriculumId` |
| `GET /learning-sessions/current` | 없음 |
| `POST /{id}/items/{itemNo}/answers` (`Idempotency-Key` 필수) | 없음 — `/{id}/completion` 일괄 |
| `POST /{id}/analysis`, `GET /{id}/result` | 없음 |
| `/review-sessions` 없음 (복습은 `sourceLectureId` 문맥) | 별도 엔드포인트로 존재 |

**구현된 계약에 맞추기로 했다.** 명세를 되살리는 것은 백엔드 담당과 조율할 별도 작업이다.
이 문서는 앱을 구현에 맞추되, 어긋난 지점을 기록해 나중에 되돌릴 수 있게 한다.

### 2.5 규칙 단위 피드백은 이번에 못 만든다

"받침 규칙에서 틀렸어요" 같은 진단을 문항마다 주려면 틀린 셀을 규칙에 귀속시켜야 한다.
그 매핑이 어디에도 저장돼 있지 않다.

```java
public class ProblemSkill {   // 문제 단위. 셀 인덱스가 없다
  private Problem problem;
  private LearningSkill skill;
}
```

귀속을 계산할 수 있는 곳은 AI 서비스뿐이다. `kbraille.analyze()` 는 셀마다 `kc`("초성:ㄱ",
"약자:ㄱㅏ")와 `rule`("제13항")을 이미 만들고, `backend_services.py:54` 가 `kc_all` 로 뭉쳐 버리고 있다.
즉 데이터는 있고 내보내지 않을 뿐이다.

그런데 앱은 AI를 직접 부를 수 없다. `deploy/nginx/itda.conf:79` 의 AI 리스너는 컨테이너
대역에서만 접근할 수 있고 외부는 UFW가 막는다. 이는 올바른 설계이므로 뚫지 않는다.

따라서 규칙 피드백을 앱에 전달할 경로는 백엔드뿐이다.

**그 경로는 다른 팀원이 작업 중이다** (2026-09-24 확인). 문항을 하나 풀 때마다 백엔드로 보내고,
백엔드가 AI에 물어 어떤 규칙을 틀렸는지 받아 앱에 돌려주는 흐름이다. 이 문서의 작업은 거기에
손대지 않는다.

두 작업이 겹치지 않는 지점을 여기에 못 박아 둔다.

| | 담당 |
|---|---|
| 문항별 규칙 피드백 (저장하지 않음) | 다른 팀원 |
| **세션 전체 저장·최종 채점** | 이 작업의 `/completion` |

**쓰기 경로는 `/completion` 하나뿐이어야 한다.** 문항별 엔드포인트가 답안을 저장하기 시작하면
같은 답이 두 번 기록되거나, 어느 쪽이 진실인지 알 수 없게 된다. 그쪽 작업이 저장을 시작한다면
이 작업의 `/completion` 과 역할을 다시 나눠야 한다 — 그때는 두 작업이 함께 결정할 일이다.

그 엔드포인트가 들어오면 앱의 로컬 XOR 피드백(3절)은 그것으로 교체된다. 이 작업의 로컬 채점은
그때까지의 임시 수단이다.

### 2.6 규칙별 누적 기록이 아무 데서도 쓰이지 않는다

`UserSkillMemory` 와 `UserSkillErrorCount` 는 `reviewWithNewSession` 에서 읽기만 하고 쓰는 코드가
없다. `/reviewAnalyze` 는 항상 빈 숙련도 상태를 받는다. 복습 문제 선택이 근거 없이 돌고 있다.

이번 범위 밖이다. 별도 작업으로 다룬다.

## 3. 책임 경계

| | 담당 |
|---|---|
| 세션 생성·문항 배정 | 백엔드 |
| 문항별 즉시 피드백 | 앱 — 로컬 XOR (`gradeAnswer`). **잠정** |
| 최종 채점·기록 | 백엔드 — `/completion` 응답이 진실 |

앱이 로컬 채점을 유지하는 이유는 문항마다 즉시 낭독·촉각 피드백이 필요한데 왕복할 엔드포인트가
없기 때문이다. 서버가 `expectedPatterns` 를 내려주므로 계산은 가능하다.

**결과 화면은 로컬 값을 쓰지 않고 서버 응답만 쓴다.** 두 채점이 갈라져도 사용자가 보는 것은
하나여야 한다. 로컬 값은 그 문항의 즉시 반응에서만 수명을 다한다.

## 4. 화면 흐름

```
intro ──start──> play ⇄ grade ──마지막──> analyzing ──> summary
  │                                          │
  └ POST /learning-sessions                  └ POST /{id}/completion
```

`analyzing` 은 이미 "분석 중이에요" 대기 화면으로 존재하고 지금은 1.8초 가짜 지연만 돈다.
제출은 여기에 들어간다.

현재 이 화면은 무조건 `summary` 로 넘어간다. 제출이 실패해도 결과가 뜬다. 실패하면 넘어가지 않고
재시도를 안내하도록 바꾼다.

## 5. 백엔드 수정 (3건)

### 5.1 보호 체인에 세 경로 추가

```java
private static final String SESSIONS_PATH   = "/learning-sessions";
private static final String SESSION_PATH    = "/learning-sessions/{sessionId}";
private static final String COMPLETION_PATH = "/learning-sessions/{sessionId}/completion";
```

`securityMatcher` 의 `OrRequestMatcher` 에 세 줄을 더한다. 기존 항목과 같이 **method까지**
지정한다 — 경로만 넣으면 같은 경로의 다른 method가 이 체인에 들어와, 계약상 405여야 할 요청이
401이 된다(`SecurityConfig` 의 기존 주석이 밝히는 이유다).

### 5.2 중복 프리픽스 제거

```java
@RequestMapping("/api/v1/learning-sessions")   // 지금
@RequestMapping("/learning-sessions")          // 고침
```

### 5.3 `ProblemDetail` 에 `problemSessionId` 추가

```java
public record ProblemDetail(
    Long problemSessionId,
    Long problemId, Long sequence, String targetText,
    List<Integer> expectedPatterns, Boolean completed) {}
```

값은 이미 손에 있다. `learningWithNewSession` 은 `saveAll` 직후의 `problemSessions` 를,
`studyWithOldSession` 은 이미 순회 중인 `ProblemSession s` 를 쓴다. `reviewWithNewSession` 도 같다.

`registerProblem`(`ProblemService.java:64`)이 만드는 `ProblemDetail` 은 지역 변수에 담고 쓰지 않는
죽은 값이다(`// TODO:수강생들에게 문제 전달`). 컴파일을 위해 `null` 을 넘기고 TODO는 건드리지
않는다 — 남의 미완성 코드를 이 작업에서 정리하지 않는다.

## 6. 계약 (`front/packages/contracts`)

엔드포인트 3개의 zod 스키마를 추가한다. 스키마가 프론트·백엔드 계약의 단일 출처라는 기존 원칙을
따른다.

**ID는 숫자로 받는다.** 백엔드가 `Long` 을 JSON 숫자로 보낸다. 명세(`api-spec.md:737`)는 "모든
BIGINT 식별자는 10진수 문자열"을 요구하지만 구현에 맞추기로 했다. JS 안전 정수 한계(2^53)를 넘으면
조용히 깨지므로 스키마에 주석으로 남긴다.

**`inputPatterns` 는 6비트 정수 배열이다.** 백엔드가 `@Min(0) @Max(63)` 으로 검증한다. 앱의 `Cell`
타입이 이미 같은 표현이므로 변환이 없다. 스키마에서도 같은 범위를 강제해, 잘못된 값이 네트워크까지
가지 않게 한다.

## 7. 앱 변경

- **`learningService`** — 세션 관련 목을 실제 호출로 교체. `getHomeSummary`·`getSkillLevels` 는
  대응 엔드포인트가 없어 목으로 남는다. 목과 실제가 한 모듈에 섞이므로 어느 쪽인지 주석으로
  분명히 한다.
- **`sessionStore`** — `sessionId` 와 문항별 `problemSessionId` 를 보관한다. 지금 `AnswerRecord` 는
  `problemId` 를 들고 있는데, 완료 제출이 요구하는 것은 `problemSessionId` 다. 앱은 둘 사이의 대응을
  알지 못하므로(서버만 안다) 받은 값을 문항 순서 그대로 들고 있어야 한다.
- **`summary`** — 로컬 `correctCount()` 대신 서버의 `correctProblemCount`·`totalProblemCount` 를
  읽는다.

## 8. 오류 처리와 접근성

기존 인증 연동에서 세운 원칙을 그대로 따른다.

- 실패하는 모든 경로에 `announce()` + `hapticError()` 를 붙인다. 화면 표시만으로는 스크린리더
  사용자에게 아무 일도 일어나지 않은 것과 같다.
- 낭독과 화면 표시를 함께 쓴다. `announce()` 만 쓰면 음성 모드가 꺼져 있을 때 실패가 보이지 않는다.
- 제출 실패는 결과 화면으로 넘기지 않는다. 사용자가 푼 것을 잃지 않도록 재시도를 제공한다.

## 9. 이번에 안 되는 것

구현하지 않기로 한 것을 명시한다. 나중에 "왜 안 되지"를 다시 조사하지 않기 위해서다.

- **이어하기 전부.** `GET /learning-sessions/current` 가 없고 앱이 `sessionId` 를 기기에 저장하지
  않는다. 홈의 "이어서 학습"은 목으로 남는다.

  `GET /{sessionId}` 도 앱이 호출하지 않는다. 계획을 짜면서 부를 자리를 찾지 못했다 — 세션을
  만든 `intro` 부터 결과까지 스토어가 메모리로 들고 가므로 같은 실행 안에서는 다시 받을 일이
  없고, 실행을 넘기는 이어하기는 기기 저장이 있어야 한다. 쓰지 않을 함수를 미리 만들지 않는다.
  다만 **인증 보호에는 포함한다** — 앱이 부르지 않는다고 해서 열어 둘 이유는 없다.
- **문제 부족 시 안내.** `ProblemService.java:151` 이 `RuntimeException` 을 던져 500이 나가고, 앱은
  "일시적인 오류"로만 말한다. 명세는 이 경우를 503으로 정의하지만(`api-spec.md:728`) 백엔드를 더
  고치지 않기로 했다.
- **규칙 단위 피드백** (2.5절) — 다른 팀원이 작업 중이다. 이 작업은 손대지 않는다.
- **`theme` 없는 진입점의 단계 구분.** `/session/intro` 로 `theme` 없이 오는 자리가 **4개 파일 8곳**
  있다 — `(tabs)/index.tsx:99,227,269`, `(tabs)/learn.tsx:76,82`, `class/review.tsx:47`,
  `tools/skills.tsx:49,134`. `learningService.startSession` 의 `theme ?? "consonant"` 때문에 여덟 곳
  전부 조용히 **자음** 세션이 된다 — "복습 시작"을 눌러도 자음이 나온다. 사용자에게는 오작동으로
  보이지만 제대로 고치려면 복습 API(`/review-sessions`) 연동이 필요하다. 그 연동 때 fallback을 지운다.
- **약어·문장 단계는 항상 500이다.** 세션 생성은 문제 12개를 요구하는데(`ProblemService.java:151`)
  서버에 있는 문제가 약어 5개·문장 0개뿐이다. 학습 주제 6개 중 2개가 고르는 즉시 실패한다 —
  코드가 아니라 시드 데이터 문제다. 실패 자체는 V32 안내로 전달된다(8절).
- **기대 셀 뒤쪽이 빈 칸(`0`)인 문항의 채점 불일치.** 답이 모자라면 앱은 `buildAttempts` 에서 빈 셀로
  채워 보낸다. 보통은 로컬·서버 판정이 같지만, 기대 셀의 뒤쪽이 `0` 이면 갈라진다 — 로컬 `gradeAnswer`
  는 길이 미달(`actual.length >= total`)을 오답으로 보는데 서버는 셀 값만 비교해 정답으로 센다.
  **지금 시드에는 `expected_pattern = 0` 인 행이 없어 발현하지 않는다**(DB 확인). 빈 칸이 섞이기 쉬운
  문장 단계 시드가 들어오면 발현한다. 고치려면 `gradeAnswer` 의 길이 규칙을 바꿔야 하는데 그 함수는
  실시간 수업도 쓰는 공유 로직이라 그쪽과 함께 결정해야 한다 — 이번 작업에서는 하지 않는다.
- **규칙별 누적 기록** (2.6절), **복습 세션**, **교육자 문제 등록**.

## 10. 완료 기준

"했다"가 아니라 "참임을 확인했다"로 쓴다.

**백엔드**

- 세 경로에 토큰 없이 요청하면 **401** 이다 (지금은 500).
- `POST /api/v1/learning-sessions/{id}/completion` 이 응답한다 (지금은 404).
- 세션 생성 응답의 각 문항에 `problemSessionId` 가 있고, 그 값으로 만든 완료 요청이 통과한다.
- `GET /learning-sessions/{sessionId}` 도 토큰 없이는 401이다 (앱이 부르지 않아도 막혀 있어야 한다).
- `./gradlew test` 와 `spotlessCheck` 가 통과한다.

**앱**

- `pnpm -r typecheck` 와 `pnpm -r test` 가 통과한다.
- `docker build --target test front` 가 통과한다.
- `sessionStore` 가 만든 완료 페이로드가 백엔드 검증(`@NotEmpty`, `0..63`)을 통과한다 — 테스트로
  확인한다.

**실기기**

- 학습 시작 → 문항 풀이 → 제출 → 결과까지 한 바퀴가 동작한다.
- 결과 화면의 정답 수가 서버 응답과 일치한다.
- 제출을 실패시키면(네트워크 차단) 결과 화면으로 넘어가지 않고 재시도를 안내한다.
