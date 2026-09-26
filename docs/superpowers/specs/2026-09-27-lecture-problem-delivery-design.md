# 실시간 수업 문제 출제·답안 설계

작성일: 2026-09-27
대상: `apps/backend`, `front/apps/web`, `front/apps/mobile`, `docs/product/api-spec.md`
선행: 실시간 수업 MVP(`2026-09-26-live-lecture-mvp-design.md`), 양방향 음성(`2026-09-26-lecture-two-way-voice-design.md`)

강사 웹 03 화면의 목 데이터(현재 문제·문제 세트·제출 현황·학생 답안)를 실제 데이터로 바꾼다.
강사가 글자를 입력해 수업 문제를 만들고, 골라서 출제하면 학습자 앱에 그 문제가 뜬다.
학습자가 제출하면 서버가 채점·저장하고, 강사 웹 학생 카드에 제출한 점자와 결과가 보인다.

## 1. 결정 사항 (사용자 확정)

| 질문 | 결정 |
| --- | --- |
| 문제를 만드는 방식 | 강사가 글자를 입력한다. 기존 문제 등록 API(`POST /lectures/{id}/problems`, `targetText`)를 고쳐 쓴다. 문제 은행 검색은 범위 밖 |
| 낱자·약자 구분 입력 | `초성 ㄱ`, `받침 ㄱ`, `모음 ㅏ`, `음절 가`처럼 **구분어 + 공백 + 글자**. 강사 화면 입력칸 위에 이 규칙을 안내한다 |
| `음절`의 뜻 | 약자 문제(level `CONTRACTION`). 구분어 없는 `가`·`그리고`는 낱말 문제 |
| 구분어 없이 낱자만 입력 (`ㄱ`) | 후보가 둘 이상이면 강사에게 고르게 한다(409 + 후보 목록). 하나면 바로 연결 |
| 등록 응답 | 연결된 문제 정보와 칸별 정답 점형(`expectedPattern`)을 돌려준다 |
| 중복 연결 | 이 수업에 이미 연결된 문제인지 먼저 확인하고, 없을 때만 연결한다 |
| 실시간 전달 | LiveKit 방 기능 재사용 — 출제는 방 metadata, 제출은 강사에게만 보내는 데이터 메시지. 클라이언트는 신호를 받으면 HTTP로 최신 상태를 조회한다. 명세 8.3 WebSocket은 후속 |

## 2. 흐름

```
강사 웹 ─ "초성 ㄱ" 입력 ─▶ POST /lectures/{id}/problems {"targetText":"초성 ㄱ"}
backend ─ 문제 찾기(없는 낱말은 AI로 생성) → 미연결일 때만 lecture_problems 저장 → 201/200 + expectedPattern
강사 웹 ─ 목록에서 골라 "출제" ─▶ PUT /lectures/{id}/current-problem {"problemId":"26578"}
backend ─ lectures.current_lecture_problem_id 저장 → 방 metadata {"status":"OPEN","currentProblemId":"26578"}
                                        │ RoomEvent.RoomMetadataChanged
학습자 앱 ◀─────────────────────────────┘ GET /lectures/{id} → currentProblem → 대기 → 풀이 화면 전환
학습자 앱 ─ 제출 ─▶ POST /lectures/{id}/problems/{problemId}/answers (Idempotency-Key)
backend ─ 채점·problem_attempts(LIVE)·attempt_cells 저장 → 응답으로 채점 결과
        ─ 강사 identity에게만 데이터 메시지 {"type":"ANSWER_SUBMITTED","problemId":"26578"} (답 내용 없음)
강사 웹 ◀── 신호 수신 → GET /lectures/{id}/problems/{problemId}/answers → 학생 카드·제출 현황 갱신
```

신호는 "다시 조회하라"는 뜻일 뿐이다. 정답·답안·결과는 신호에 싣지 않고 권한 검사를 거친 HTTP로만 받는다.
신호를 놓쳐도 새로고침·재접속 때 HTTP 조회로 전체 화면이 복원된다.

## 3. backend

### 3.1 문제 등록 (기존 API 수정)

`POST /api/v1/lectures/{lectureId}/problems` body `{"targetText": "초성 ㄱ"}`

수정 대상(팀원 코드): `ProblemController.registerProblem`, `ProblemService.registerProblem`, `ProblemRepository`.

- 권한: 담당 INSTRUCTOR. 상태: SCHEDULED·OPEN에서 연결 가능, CLOSED는 409 `INVALID_LECTURE_STATE` (명세 8.1).
- 입력 해석 (앞뒤 공백 제거, 구분어와 글자 사이 공백 1개 이상):

| 입력 | 고르는 문제 |
| --- | --- |
| `초성 {자음}` | target_text = 자음, level CONSONANT, 학습 요소 SKILL_01(첫소리 기본 자음)에 연결된 문제 |
| `받침 {자음}` | target_text = 자음, level CONSONANT, 학습 요소 SKILL_03(받침 기본 자음)에 연결된 문제 |
| `모음 {모음}` | target_text = 모음, level VOWEL |
| `음절 {글자}` | target_text = 글자, level CONTRACTION |
| 구분어 없음, 낱자(ㄱ~ㅎ, ㅏ~ㅣ 등 호환 자모) 한 글자 | 해당 글자의 CONSONANT·VOWEL 후보. 둘 이상이면 409 `PROBLEM_TEXT_AMBIGUOUS` + 후보, 하나면 그 문제 |
| 구분어 없음, 그 밖 | target_text 일치 문제 중 level WORD·CONTRACTION_WORD·SENTENCE를 우선, id 오름차순 첫 번째. 없으면 기존대로 AI 분석으로 새 문제 생성 |

- 구분어가 붙은 입력에 해당 문제가 없으면 404 `PROBLEM_NOT_FOUND` (자모·약자는 DB에 고정된 목록이므로 AI로 만들지 않는다).
- 같은 target_text 중복(`아`·`오`·`이` WORD 2건 등)은 id 오름차순 첫 번째. 데이터 정리는 범위 밖.
- 중복 연결: `lecture_problems`에 (lecture, problem)이 있으면 저장하지 않고 200, 없으면 저장하고 201. 동시 요청으로 UNIQUE 위반이 나면 200으로 처리한다.
- 응답 (`LinkedProblemResponse`):

```json
{
  "problemId": "26578",
  "targetText": "ㄱ",
  "displayText": "초성 ㄱ",
  "cellCount": 1,
  "expectedPattern": [8]
}
```

`displayText`는 구분어가 필요한 문제(초성·받침·모음·음절)에 구분어를 붙인 표시용 문자열이다. 앱의 문제 안내와 웹 표시에 쓴다.
구분 규칙은 문제의 level과 학습 요소로 서버가 정한다 — 입력 문자열을 그대로 저장하지 않는다.

- 409 `PROBLEM_TEXT_AMBIGUOUS` body: `{"code","message","candidates":[{"input":"초성 ㄱ","displayText":"초성 ㄱ"},{"input":"받침 ㄱ","displayText":"받침 ㄱ"}]}`.
  공통 오류 body에 `candidates`만 추가한다. 웹은 후보 버튼을 누르면 `input`으로 다시 등록한다.
- 기존 `// TODO:수강생들에게 문제 전달`은 지운다 — 전달은 3.2가 맡는다.

### 3.2 출제 (신규, 명세 8.2 기존 계약)

`PUT /api/v1/lectures/{lectureId}/current-problem` body `{"problemId": "26578"}` → 200 강사 Snapshot

- 담당 INSTRUCTOR, OPEN 전용(그 밖 409). 이 수업에 연결되지 않은 문제면 404 `PROBLEM_NOT_FOUND`.
- `Lecture.deliverProblem(lectureProblemId)`(이미 있음)로 저장한다. 같은 값이면 DB 저장을 생략한다(멱등).
- 커밋 후 방 metadata를 `{"status":"OPEN","currentProblemId":"<problemId>"}`로 쓴다. 같은 값 재요청에도 metadata 쓰기는 수행한다 —
  앞선 요청의 metadata 갱신이 실패(503 `MEDIA_SERVER_UNAVAILABLE`)했을 때 강사가 다시 눌러 복구하기 위해서다.
  값이 같으면 LiveKit이 변경 이벤트를 내지 않으므로 학습자 화면이 다시 바뀌지 않는다.
- 강사 토큰 발급 때 방을 다시 여는 로직(`LectureMediaService`)도 현재 문제를 metadata에 포함한다.

### 3.3 답안 제출 (신규, 명세 8.1·8.2 기존 계약)

`POST /api/v1/lectures/{lectureId}/problems/{problemId}/answers` header `Idempotency-Key: <UUID v4>` body `{"cells":[8],"durationMs":1900}`

- 활성 LEARNER(left_at NULL), OPEN, 경로의 문제가 현재 문제일 때만. 오류는 명세 8.4 표 그대로
  (`LEARNER_ACCESS_REQUIRED`, `LECTURE_PARTICIPATION_REQUIRED`, `INVALID_LECTURE_STATE`, `LECTURE_PROBLEM_NOT_CURRENT`,
  `LECTURE_PROBLEM_ALREADY_ANSWERED`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_ANSWER_CELLS`).
- 채점: 서버에서 칸별로 비교한다. `diffMask = input XOR expected`, 칸 수가 다르면 오답, 모든 칸 diffMask 0이면 정답. AI 분석·BKT·user_skill_memory 갱신은 하지 않는다 (명세 8.1 LIVE 제외).
- 저장: `ProblemAttempt.forLiveLecture(...)`(이미 있음) + `attempt_cells`. (lecture_id, user_id, problem_id) UNIQUE.
- 멱등: 같은 학습자·문제에 이미 풀이가 있으면 — 같은 Idempotency-Key·같은 cells면 200 기존 결과, 다른 키면 409 `LECTURE_PROBLEM_ALREADY_ANSWERED`,
  같은 키·다른 cells면 409 `IDEMPOTENCY_KEY_REUSED`. 키 저장 위치는 계획에서 정한다(problem_attempts에 컬럼 추가 또는 Redis).
- 종료와의 직렬화: 수업 행을 잠그고(`SELECT ... FOR UPDATE`) OPEN·현재 문제를 확인한 뒤 저장한다 (명세 8.1).
- 응답 201: `attemptId, lectureId, problemId, isCorrect, score, attemptedAt, cells[{cellIndex, inputPattern, expectedPattern, diffMask, isCorrect}]`.
- 커밋 후 강사 identity(`user-{instructorId}`)에게만 LiveKit 데이터 메시지 `{"type":"ANSWER_SUBMITTED","problemId":"…"}`(topic `lecture`)를 보낸다.
  실패는 로그만 남기고 제출은 성공으로 응답한다 — 강사 웹은 출제·새로고침 때 다시 조회한다.

### 3.4 강사 답안 목록 (신규 — 명세 추가)

`GET /api/v1/lectures/{lectureId}/problems/{problemId}/answers` → 200

- 담당 INSTRUCTOR, 연결된 문제만(아니면 404 `PROBLEM_NOT_FOUND`). 상태 무관(CLOSED 뒤에도 조회 가능).
- 응답: `{"problemId","answers":[{"userId","username","isCorrect","durationMs","attemptedAt","cells":[{cellIndex,inputPattern,expectedPattern,diffMask,isCorrect}]}]}`.
  제출한 학습자만 담는다. 미제출·미입장 판정은 웹이 접속자 목록과 맞춰 한다.

### 3.5 강사·학습자 Snapshot 채우기 (기존 필드)

- 강사 `GET /lectures/{id}`: `currentProblem`(3.1 응답 형태) · `linkedProblems`(연결 순서대로 3.1 응답 형태 배열).
- 학습자 `GET /lectures/{id}`: `currentProblem` = `{problemId, problemType(=problems.level), targetText, displayText, cellCount, submitted}`,
  제출했으면 `myResult`(3.3 응답 형태). 정답 점형은 제출 전 학습자에게 주지 않는다 (명세 8.2).

## 4. 강사 웹 (03 화면)

- **문제 세트 패널(오른쪽)**
  - 입력칸 + "추가" 버튼. 입력칸 위 안내:
    "초성 ㄱ은 '초성'을 입력하고 한 칸 띄운 뒤 ㄱ을 입력하세요. 받침 ㄱ, 모음 ㅏ, 음절 가도 같은 방식이에요."
  - 409 후보가 오면 입력칸 아래에 후보 버튼(`초성 ㄱ` / `받침 ㄱ`)을 보이고, 누르면 그 `input`으로 다시 등록한다.
  - 목록: 연결 순서. 상태는 **출제됨**(한 번이라도 현재 문제였던 것 — 웹 세션에서 기억, 새로고침 시 현재 문제만 표시) / **지금 문제** / **대기**.
    대기 항목을 고르고 "출제" → 3.2 호출. SCHEDULED에서는 추가만 되고 출제 버튼은 비활성("수업을 시작하면 출제할 수 있어요").
- **현재 문제 카드(위)**: `displayText`, 정답 점형 문자열(`1·3·5 / 2·4·6`), 점자 그림. 없으면 "오른쪽에서 문제를 골라 출제하세요".
- **제출 현황 카드**: 제출 N / 접속 학생 M, 정답·오답·미착수(=접속 학생 − 제출). 답안 목록과 LiveKit 접속자 목록으로 계산.
- **학생 카드**: 점선 "예시" 칸을 실제 답안 칸으로 바꾼다.
  - 정답: 초록, 제출 점자(유니코드), 걸린 시간 `1.9초` / 오답: 노랑, 제출 점자, 틀린 이유 / 미제출: 회색 "미착수"
  - 틀린 이유(순수 함수): 칸 수가 다르면 "칸 수 다름", 아니면 첫 틀린 칸 기준 "첫 칸 6점 누락"·"둘째 칸 3점 추가"·"첫 칸 1점 누락, 4점 추가"
  - 접속했지만 답안 목록에 없는 학생은 미착수. 답안은 있지만 지금 접속하지 않은 학생 카드는 두지 않는다(접속자 기준 명단 유지).
- **갱신**: 출제 직후, `ANSWER_SUBMITTED` 신호 수신(현재 문제와 같은 problemId일 때), 페이지 진입·새로고침 때 답안 목록 조회.
  같은 조회가 겹치면 마지막 응답만 반영한다(세대 번호).
- **제거**(데이터 없음): 장치 연결 점, 미입장 학생, 출제됨 문제의 반 정답률, 목 파일 `liveMock.ts`.

## 5. 학습자 앱

- **수업 시작 후 풀이 화면은 문제 대기 상태로 시작**한다: "선생님 문제를 기다리는 중이에요"(낭독). 손들기·마이크 버튼은 그대로 둔다.
  현재 앱의 로컬 문제 세트(`useSessionStore`)로 바로 풀이하던 흐름을 서버 현재 문제로 바꾼다.
- **출제 감지**: `liveLecture`가 `RoomEvent.RoomMetadataChanged`에서 `currentProblemId`를 읽어 바뀌면 콜백을 부른다(세대 번호 규칙 유지).
  화면은 `GET /lectures/{id}`로 `currentProblem`을 받아 풀이로 전환하고 "선생님 문제예요. '{displayText}'을 입력하세요"를 낭독한다.
  입장 직후·재접속 때도 한 번 조회한다.
- **제출**: 길게 눌러 제출 → 마이크가 켜져 있으면 끄고(기존) → `POST answers`(문제마다 새 UUID v4 키, 재시도는 같은 키).
  채점 화면은 서버 응답으로 결과를 낭독하고, 확인하면 대기 상태로 돌아간다. 채점 화면에 있는 동안 새 문제가 오면 안내 후 풀이로 전환한다.
- **이미 제출한 현재 문제**(재접속 등, `submitted=true`): "제출했어요. 다음 문제를 기다려요" 대기 상태.
- 409 `LECTURE_PROBLEM_NOT_CURRENT`(그 사이 다음 문제로 바뀜): "문제가 바뀌었어요" 안내 후 현재 문제를 다시 조회한다.

## 6. 명세 변경

- `api-spec.md` §8.2: 문제 연결 요청을 `{"targetText"}`와 구분어 규칙으로, 응답 201/200 + 문제 정보(`expectedPattern`, `displayText`). 강사 답안 목록 endpoint 추가.
  "현행 계약에는 참여자 상세 목록·정답률·대시보드 응답이 없다" 문장을 "강사는 문제별 답안 목록을 조회한다"로 바꾼다.
- §8.3: MVP는 WebSocket 대신 LiveKit 방 metadata(`currentProblemId`)와 강사 대상 데이터 메시지(`ANSWER_SUBMITTED`)로 알린다는 절을 추가하고, WebSocket 계약은 후속으로 표시.
- §8.4 오류 표: `PROBLEM_TEXT_AMBIGUOUS`(409) 추가.
- §8.5: 방 metadata 규약에 `currentProblemId` 추가.

## 7. 브랜치·MR

- `feat/lecture-problem` — 양방향 음성 변경이 합쳐진 로컬 E2E 브랜치 위에서 시작한다. 음성 MR이 dev에 들어간 뒤 dev를 병합하면 MR 차이는 이 기능만 남는다.
- 하위 브랜치(API / 웹 / 앱)를 나누고 각각 `feat/lecture-problem`으로 MR 한다. 웹·앱은 계약(본 문서 §3)에만 의존하므로 API와 병행 구현한다.
- 문제 등록 API 수정은 팀원 코드 변경이므로 MR에 담당자를 리뷰어로 지정한다.

## 8. 완료 기준

- backend `./gradlew test`: 입력 해석(구분어·후보·우선순위), 중복 연결 200/201, 출제 권한·상태·멱등·metadata, 제출 채점·멱등·오류·잠금, 답안 목록 권한 테스트
- web `pnpm --filter @ieum/web test/lint/build`, mobile `jest`·`typecheck`, `docker build --target test front` 통과
- 수동 E2E(실서버·실기기):
  1. 강사가 `초성 ㄱ`·`그리고`를 추가하면 목록에 뜨고 정답 점형이 보인다. `ㄱ`만 입력하면 후보 버튼이 나온다. 같은 글자를 다시 넣어도 목록이 늘지 않는다
  2. 학습자는 수업 시작 뒤 "문제를 기다리는 중" 화면을 본다
  3. 강사가 출제하면 앱이 풀이 화면으로 바뀌고 문제를 읽는다
  4. 학습자가 제출하면 앱은 서버 채점 결과를 읽고, 강사 웹 학생 카드에 제출 점자·정답/오답·시간(또는 틀린 이유)이 뜨고 제출 현황 숫자가 바뀐다
  5. 강사가 다음 문제를 출제하면 채점 화면·대기 화면의 학습자가 새 문제로 넘어간다
  6. 강사 웹 새로고침·앱 재접속 뒤에도 현재 문제와 답안이 복원된다

## 9. 범위 밖

- 문제 은행 검색·문제 순서 편집·연결 해제 UI, 출제됨 문제의 반 정답률
- 점자 장치 연결 상태 표시, 미입장 학생 명단(참여자 목록 API)
- 명세 8.3 WebSocket 구현
- 문제 데이터 중복 정리(`아`·`오`·`이` 등), 자모 target_text 표기 변경
- 수업 후 복습 연결(`reviewableCurriculumStages`)
