# 문항별 분석 연동 설계

작성일: 2026-09-25
대상: `front/apps/mobile`, `front/packages/contracts`, `apps/backend`

학습자 앱의 제출 흐름을 dev의 새 계약에 맞춘다. 문항을 하나 풀 때마다 서버로 보내 AI 규칙
분석을 받고, 마지막에 세션을 완료한다.

앞선 작업(`2026-09-24-learning-session-integration-design.md`)은 일괄 제출만으로 한 바퀴를
돌리는 설계였다. 그 전제가 dev 병합으로 무너졌다.

## 1. 왜 바꾸는가

`/completion` 의 거부 조건이 뒤집혔다.

```java
// LearningSessionCompletionService:208
|| assignedProblems.stream().anyMatch(assigned -> !assigned.isCompleted())
```

전에는 "이미 완료된 세션이면 거부"였는데 지금은 **"완료되지 않은 문항이 하나라도 있으면
거부"** 다. `ProblemSession.completed` 를 찍는 곳은 `LearningAttemptService:98` 의
`assigned.complete()` 하나뿐이고, 그것은 `POST /{sessionId}/attempt-analysis` 가 부른다.

즉 **문항별 호출 없이는 세션을 완료할 수 없다.** 실기기에서 12문항을 다 푼 뒤 422가 났고
(세션 11, 배정 12개 전부 `completed=0`), 그것이 이 설계의 출발점이다.

`/completion` 도 역할이 바뀌었다. 더 이상 답안을 저장하지 않는다 — `attempt-analysis` 가
저장한 것을 읽어 검증하고(`validateStoredAttempts`) 세션 요약을 만든다.

## 2. 새 계약

```
POST /api/v1/learning-sessions/{sessionId}/attempt-analysis
  { problemSessionId, inputPatterns[] }
  → { problemSessionId, problemId, attemptId, correct, attemptedAt,
      cells[]{cellIndex, expectedPattern, inputPattern, diffMask,
              missingMask, extraMask, missingDots[], extraDots[]},
      feedback{ tts, detail } }

POST /api/v1/learning-sessions/{sessionId}/completion
  { attempts[]{problemSessionId, inputPatterns[]} }
  → 기존 필드 + summary{ summary, ttsText, errorTypeCounts, weakSkills[] }
```

### 2.1 재시도 안전성

`analyze()` 는 `@Transactional` 이다. 실패하면 `assigned.complete()` 까지 롤백되므로 **같은
답을 다시 보내는 것이 안전하다.**

반대로 한 번 성공한 문항은 다시 보낼 수 없다 — `assigned.isCompleted()` 이면
`LEARNING_PROBLEM_ALREADY_ANALYZED` 로 거부한다. 앱은 이것을 **오류가 아니라 "이미 처리됨"**
으로 다뤄야 한다(3절).

### 2.2 입력 12칸 상한

AI가 입력을 12칸까지만 받는다.

```python
# apps/ai/.../backend/router.py
input_patterns: list[int] = Field(default_factory=list, max_length=12)
```

`expected_patterns` 에는 상한이 없다. 그래서 **13칸 이상이 필요한 문제는 정답을 넣어도
거부된다.**

DB 조회 결과 단어 단계 26,576개 중 **486개가 13칸 이상**이다(1.8%). 12문항 세션이면 한
문항이라도 걸릴 확률이 대략 20%이고, 실제로 세션 11에 15칸 문제(id 12214)가 배정됐다.

걸리는 항목들의 성격:

| target_text | 칸 수 |
|---|---|
| 특정경제범죄가중처벌법상 | 27 |
| 과학기술정보방송통신위원회 | 26 |
| 한국디스플레이산업협회는 | 25 |

`단어` 단계인데 실제로는 긴 복합어·기관명이다. 시드가 단어 사전이 아니라 말뭉치에서 온 것으로
보인다. 그 자체가 학습 문제로 적절한지는 시드를 손볼 때 볼 일이고, 이 설계는 **FastAPI의
상한에 맞춘다**.

## 3. 책임 경계

| | 지금 | 바뀜 |
|---|---|---|
| 문항 채점 | 앱의 로컬 XOR | **서버** |
| 규칙 설명 | 없음 | 서버 `feedback` |
| `/completion` | 답안 저장 | **검증·요약만** |
| 로컬 `gradeAnswer` | 채점 권위 | 입력 가드·오류 시 설명 근거 |

`gradeAnswer` 를 버리지 않는다. `play` 가 "몇 칸 입력했는지"를 말할 때 쓰고, 서버가 죽었을 때
사용자에게 무슨 일이 있었는지 말할 근거가 된다. 다만 **정답 여부는 더 이상 로컬이 정하지
않는다.**

## 4. 화면별 동작

```
play ──제출──> (대기) ──> grade ──> play … ──마지막──> analyzing ──> summary
       │                                                   │
       └ POST /{id}/attempt-analysis                       └ POST /{id}/completion
```

### 4.1 `play` — 제출과 대기

**화면을 떠나지 않고 그 자리에서 기다린다.** 실패했을 때 답안이 이 화면에 남아 있어야
재시도할 수 있다.

- 제출 즉시 "확인하고 있어요"를 낭독하고 화면에도 표시한다. 입력과 제출을 잠근다.
- 성공하면 응답을 스토어에 담고 `grade` 로 간다.
- 실패하면 이 화면에 머문다(5절).

**즉시 결과를 말하지 않는다.** 로컬 판정을 먼저 말하면 서버 판정과 갈릴 때 "맞았어요" 뒤에
"틀렸어요"가 따라붙는다 — 낭독으로만 듣는 사용자에게 가장 나쁜 실패다.

입력 가드가 바뀐다.

- **기대 칸 수 초과 차단을 없앤다.** dev가 길이 동등 검사를 뺐으므로(`validateExpectedCells`)
  칸 수가 다른 답은 이제 오답으로 채점된다. 막을 이유가 사라졌다.
- **12칸 상한을 둔다.** FastAPI가 그 이상을 받지 않는다. 5칸 문제에 13칸을 넣어도 거부되므로
  기대 칸 수와 무관하게 필요하다.

### 4.2 `grade` — 서버 응답만 읽는다

낭독 순서를 정한다. 점 수준이 먼저고 규칙이 뒤다 — 방금 틀린 것을 바로 고칠 수 있게 하고,
이유는 그 뒤에 설명한다.

```
정답이에요.
오답이에요. 첫 번째 칸의 정답은 1번, 3번이에요. 3번 점이 빠졌어요.
           두 번째 칸의 정답은 2번, 5번이에요. 4번 점이 더 눌렸어요.
           첫소리 이응은 점자로 적지 않아요.        ← 서버 feedback.tts
```

앞의 점 수준 문장은 앱이 `cells[]` 로 만든다. 마지막 규칙 설명은 서버 것을 그대로 쓴다.

**화면 문구와 낭독 문구는 같은 문자열이다** (앞선 설계 8절의 규칙).

칸이 많으면 낭독이 길어진다. 앞 3칸만 자세히 말하고 나머지는 "그 외 N칸도 달라요"로 줄인다 —
지금 `describeDiff` 가 쓰는 방식 그대로다.

### 4.3 `analyzing` · `summary`

`analyzing` 은 그대로 `/completion` 을 부른다. 역할이 저장에서 검증·요약으로 바뀌었을 뿐
앱에서 보이는 동작은 같다.

`summary` 는 서버의 `summary.ttsText` 를 낭독하고 `weakSkills` 를 표시한다.

## 5. 오류 처리

| 상황 | 동작 |
|---|---|
| `attempt-analysis` 네트워크·서버 실패 | `play` 에 머물며 "다시 보내기". 문구에 시도 횟수를 담는다 |
| 3회 실패 | "이 문제는 지금 확인할 수 없어요. 학습을 마칠 수 없으니 처음부터 다시 해주세요." + 홈으로 |
| `LEARNING_PROBLEM_ALREADY_ANALYZED` | 오류로 보지 않고 `grade` 로 진행 |
| `/completion` 실패 | 지금과 같다 — 결과로 넘어가지 않고 재시도 + 홈으로 |

**3회에서 멈추는 이유**는 영구 실패가 실재하기 때문이다. 12칸 초과 문제(2.2절)는 재시도해도
계속 거부된다. 무한 재시도는 사용자를 가둔다.

**시도 횟수를 문구에 담는 이유**는 `a11y/speech.ts` 가 같은 문구를 1.5초 안에 중복 제거하기
때문이다. 같은 오류로 재시도가 실패하면 두 번째 낭독이 조용히 씹힌다. `analyzing.tsx` 의
`buildRetryFailureMessage` 가 이미 같은 방식으로 풀었다.

**`ALREADY_ANALYZED` 를 오류로 보지 않는 이유**: 앱이 응답을 못 받았지만 서버는 성공한
경우다(응답 유실, 앱 재시작). 그때 재시도하면 이 코드가 온다. 오류로 다루면 사용자는 이미
끝난 문항에서 막힌다.

## 6. 백엔드 수정 (1건)

**출제에서 13칸 이상 문제를 제외한다.**

```java
// ProblemRepository
List<Problem> findAllByLevelAndIdNotIn(Level level, List<Long> ids);
```

이 메서드가 후보를 뽑는다. 셀 수 조건을 더해, AI가 받을 수 없는 문제를 애초에 배정하지 않는다.
단어 단계 26,090개가 남으므로 출제에 지장이 없다.

이것은 앱이 풀 수 없는 문제다. 앱에 "13칸 이상은 건너뛰기"를 넣으면 서버가 아는 정보를 앱에
하드코딩하는 것이고, 건너뛸 수단도 없다(출제 API에 그런 개념이 없다).

## 7. 계약 (`front/packages/contracts`)

`attempt-analysis` 요청·응답 스키마를 추가한다. 기존 학습 세션 스키마와 같은 파일에 둔다.

- `inputPatterns` 는 6비트 정수 배열, **최대 12개**. 백엔드는 `@Max(63)` 만 보지만 FastAPI가
  12칸에서 막으므로 앱이 먼저 막는다.
- `cells[]` 는 완료 응답의 `CellCompletionResponse` 와 같은 모양이다. 스키마를 공유한다.
- `completion` 응답에 `summary` 를 더한다. 지금은 스키마에 없어 조용히 버려지고 있다.

## 8. 이번에 안 되는 것

- **시드 데이터 정리.** 13칸 이상 486개를 출제에서 빼기만 하고 데이터는 두었다. "특정경제범죄
  가중처벌법상"이 단어 학습 문제로 적절한지는 시드를 손볼 때 볼 일이다.
- **복습 세션**(`/review-sessions`), **이어하기**, **교육자 문제 등록** — 앞선 설계 9절 그대로.
- **theme 없는 진입점 4곳(8버튼)이 자음 세션이 되는 것** — 복습 API 연동 때 푼다.
- **약어·문장 단계의 문제 부족** — 시드 문제다.

## 9. 완료 기준

"했다"가 아니라 "참임을 확인했다"로 쓴다.

**자동 검증**

- `cells[]` 에서 점 수준 문장을 만드는 함수가 정답·한 칸 오답·여러 칸 오답·빈 입력을 덮는다.
- 제출 오케스트레이션이 성공·실패·`ALREADY_ANALYZED`·3회 소진 네 경우를 덮는다.
- 계약 스키마가 12칸 초과를 거부한다.
- `pnpm -r typecheck`, `pnpm -r test`, `docker build --target test front` 통과.
- `./gradlew test spotlessCheck` 통과.

**실기기**

- 단어 단계 12문항을 풀어 **결과 화면까지 도달한다** — 지금은 422로 막힌다.
- 여러 칸을 틀린 문항에서 칸마다 점 번호가 낭독되고, 이어서 규칙 설명이 낭독된다.
- 기대보다 적은 칸, 많은 칸을 넣어도 세션이 끝까지 간다.
- 비행기 모드로 문항 제출을 실패시키면 `play` 에 머물고 재시도가 동작한다.
- 12칸 초과 문제가 더 이상 배정되지 않는다.
