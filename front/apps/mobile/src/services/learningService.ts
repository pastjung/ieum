import {
  inProgressSessionSchema,
  problemsResponseSchema,
  skillSummarySchema,
  stageStatsSchema,
  completionResponseSchema,
  attemptAnalysisRequestSchema,
  attemptAnalysisResponseSchema,
  type AttemptAnalysisResponse,
  type AttemptInput,
  type InProgressSession,
  type CompletionResponse,
  type LearningSession,
  type ProblemsResponse,
  type SkillSummary,
  type StageStats,
} from "@ieum/contracts";
import { api } from "./apiClient";
import type { ServerSessionMeta } from "@/stores/sessionStore";

/**
 * 마지막 글자의 받침 유무로 "을"/"를"을 고른다.
 *
 * 이 앱은 낭독이 주 출력이다 — "'ㅏ'을 점자로 입력하세요"처럼 조사가 틀리면 그대로
 * 들린다. `mockData.ts`의 목 데이터는 이미 "ㅏ를"·"ㅓ를"처럼 구분해 왔으므로 실제
 * 응답을 옮기는 이 함수만 "을"로 고정하면 안 된다. 한글 완성형(U+AC00~U+D7A3) 범위는
 * `(code - 0xAC00) % 28` 이 0이면 받침이 없다(를), 아니면 있다(을). 한글 완성형이
 * 아닌 글자로 끝나는 문항이 실제로 있는지는 확인되지 않았다 — 그 경우는 "을"로 둔다.
 */
export function particleFor(text: string): "을" | "를" {
  // charCodeAt(-1)은 NaN이라 아래 두 비교가 둘 다 false가 되어 가드를 빠져나간다.
  // 그 뒤 NaN % 28 !== 0이 우연히 true가 되어 "을"이 나오지만, 의도한 경로가 아니다 —
  // 빈 문자열은 여기서 명시적으로 끊는다.
  if (!text) return "을";
  const last = text.charCodeAt(text.length - 1);
  if (last < 0xac00 || last > 0xd7a3) return "을";
  return (last - 0xac00) % 28 !== 0 ? "을" : "를";
}

/**
 * 서버 응답을 화면이 쓰는 모양으로 옮긴다.
 *
 * 서버는 지시문(prompt)과 제목(title)을 주지 않는다. 화면이 낭독할 문장이 필요하므로
 * 여기서 만든다 — 문구를 화면마다 따로 만들면 갈라진다.
 */
function toSession(
  response: ProblemsResponse,
  title: string,
): { session: LearningSession; meta: ServerSessionMeta } {
  return {
    session: {
      id: String(response.sessionId),
      title,
      problems: response.problems.map((p) => ({
        id: String(p.problemId),
        prompt: `'${p.targetText}'${particleFor(p.targetText)} 점자로 입력하세요`,
        target: p.targetText,
        expectedCells: p.expectedPatterns,
      })),
    },
    meta: {
      serverSessionId: response.sessionId,
      problemSessionIds: response.problems.map((p) => p.problemSessionId),
    },
  };
}

export const learningService = {
  /**
   * 새 학습 세션을 만든다. 서버가 문항을 배정해 함께 돌려준다.
   *
   * 단계를 `curriculumId`로 받는다. 예전에는 화면의 테마 id(`"consonant"` …)를 받아
   * 앱 안의 표로 단계 번호를 찾았는데, 그 표가 DB의 `curriculum_stages`와 따로 관리돼
   * 순서가 바뀌면 학습자가 엉뚱한 단계를 배우게 됐다. 이제 단계 목록을 서버에서 받으므로
   * (`getStageStats`) 그 표가 필요 없다.
   */
  async startSession(curriculumId: number, title: string) {
    const response = problemsResponseSchema.parse(
      await api.post("/learning-sessions", { curriculumId }),
    );
    return toSession(response, title);
  },

  /**
   * 복습 세션을 만든다. 단계를 고르지 않는다 — 서버가 내 기록(`user_skill_memory`와 오류
   * 횟수)을 FastAPI에 보내 약한 요소를 고르고 그 요소의 문항을 배정한다
   * (`ProblemService.reviewWithNewSession`).
   *
   * 응답 모양은 `startSession`과 같은 `ProblemsResponse`라 이후 흐름(제출·채점·완료)은
   * 자유 학습과 완전히 같다. 본문이 없는 POST다.
   */
  async startReviewSession() {
    const response = problemsResponseSchema.parse(await api.post("/review-sessions"));
    return toSession(response, "복습");
  },

  /**
   * 진행 중인 학습 세션. 없으면 null.
   *
   * 홈의 "이어서 학습"이 오랫동안 목 데이터("3단계 자음과 모음 · 7 / 10 문항")를 보여주고,
   * 누르면 진행 중이던 세션이 아니라 새 자음 세션을 만들었다. 이어받을 세션의 식별자를
   * 알 방법이 없었기 때문이다.
   */
  async getInProgressSession(): Promise<InProgressSession | null> {
    const payload = await api.get<unknown>("/learning-sessions/in-progress");
    // 204는 본문이 없다 — 진행 중인 것이 없다는 뜻이다
    if (payload === undefined || payload === null || payload === "") {
      return null;
    }
    return inProgressSessionSchema.parse(payload);
  },

  /**
   * 중단한 세션을 이어받는다. 새 문항을 배정하지 않고 기존 배정을 그대로 가져온다.
   *
   * **이미 푼 문항은 뺀다.** 서버는 완료된 배정에 대한 재제출을 거부하므로
   * (`LearningAttemptService`의 `LEARNING_PROBLEM_ALREADY_ANALYZED`), 그대로 두면 이어서
   * 시작하자마자 막힌다.
   */
  async resumeSession(sessionId: number) {
    const response = problemsResponseSchema.parse(
      await api.get(`/learning-sessions/${sessionId}`),
    );
    const remaining = response.problems.filter((problem) => !problem.completed);
    if (remaining.length === 0) {
      throw new Error("이어서 풀 문항이 없어요");
    }
    return toSession({ ...response, problems: remaining }, "이어서 학습");
  },

  /**
   * 내 요소별 숙련 상태와 풀이 통계.
   *
   * 지금까지 숙련 라벨은 세션 완료 응답에만 실려서, "내 실력" 화면과 홈 복습 카드는
   * 세션 밖에서 알 방법이 없어 고정값을 내 실력처럼 보여주고 있었다.
   */
  async getSkillSummary(): Promise<SkillSummary> {
    return skillSummarySchema.parse(await api.get("/learning-sessions/skill-summary"));
  },

  /**
   * 테마(커리큘럼 단계)별 풀이 통계.
   *
   * 테마 선택 화면이 `mockData.learningThemes`의 고정 숫자를 보여주고 있었다.
   */
  async getStageStats(): Promise<StageStats> {
    return stageStatsSchema.parse(await api.get("/learning-sessions/stage-stats"));
  },

  /** 세션의 모든 답안을 한 번에 제출한다. 이 응답이 최종 채점 결과다 */
  async completeSession(
    sessionId: number,
    attempts: AttemptInput[],
  ): Promise<CompletionResponse> {
    return completionResponseSchema.parse(
      await api.post(`/learning-sessions/${sessionId}/completion`, { attempts }),
    );
  },

  /**
   * 문항 하나를 제출해 서버 채점과 규칙 분석을 받는다.
   *
   * 요청도 `parse` 하는 이유: 12칸 상한(`MAX_INPUT_CELLS`)을 네트워크에 나가기 전에 막는다 —
   * AI가 어차피 12개를 넘으면 거부하므로(`apps/ai/app/presentation/api/backend/router.py`),
   * 여기서 먼저 걸러야 사용자가 왕복 지연 뒤에야 같은 오류를 본다.
   */
  async analyzeAttempt(
    sessionId: number,
    problemSessionId: number,
    inputPatterns: number[],
  ): Promise<AttemptAnalysisResponse> {
    const body = attemptAnalysisRequestSchema.parse({ problemSessionId, inputPatterns });
    return attemptAnalysisResponseSchema.parse(
      await api.post(`/learning-sessions/${sessionId}/attempt-analysis`, body),
    );
  },

  /* ─── 아래는 아직 목이다. 대응 엔드포인트가 없다 ─── */

};
