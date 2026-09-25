import {
  problemsResponseSchema,
  completionResponseSchema,
  attemptAnalysisRequestSchema,
  attemptAnalysisResponseSchema,
  type AttemptAnalysisResponse,
  type AttemptInput,
  type CompletionResponse,
  type LearningSession,
  type ProblemsResponse,
} from "@ieum/contracts";
import { api } from "./apiClient";
import type { ServerSessionMeta } from "@/stores/sessionStore";
import { homeSummary, skillLevels, type HomeSummary, type LearningThemeId } from "./mockData";

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * 화면의 학습 주제를 백엔드 curriculum_stages.id로 옮긴다.
 *
 * 목록을 내려주는 엔드포인트가 없어 여기에 적어 둔다. DB의 curriculum_stages는 1~6이고
 * 이 순서가 바뀌면 사용자가 엉뚱한 단계를 배우게 된다 — 단계가 늘거나 순서가 바뀌면
 * 조회 API를 먼저 만들고 이 표를 지운다.
 */
export const CURRICULUM_ID_BY_THEME: Record<LearningThemeId, number> = {
  consonant: 1,
  vowel: 2,
  "abbrev-char": 3,
  word: 4,
  "abbrev-word": 5,
  sentence: 6,
};

const THEME_LABEL: Record<LearningThemeId, string> = {
  consonant: "자음",
  vowel: "모음",
  "abbrev-char": "약자",
  word: "단어",
  "abbrev-word": "약어",
  sentence: "문장",
};

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
  theme: LearningThemeId | undefined,
): { session: LearningSession; meta: ServerSessionMeta } {
  return {
    session: {
      id: String(response.sessionId),
      title: theme ? THEME_LABEL[theme] : "학습",
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
   * `theme` 없이 이 화면(`/session/intro`)으로 오는 진입점이 **4개 파일 8곳** 있다 —
   * `(tabs)/index.tsx:99,227,269`("이어서 풀기"·"복습 시작"), `(tabs)/learn.tsx:76,82`의
   * 테마 없는 버튼, `class/review.tsx:47`, `tools/skills.tsx:49,134`("약한 것부터 복습").
   * 지금은 이 여덟 곳 전부 `theme ?? "consonant"`로 인해 조용히 **자음** 세션이 된다 — "복습 시작"을
   * 눌러도 실제로는 자음 학습이 시작된다. 목 시절에도 이 진입점들은 구분되지 않았으니
   * 이번 변경이 새로 만든 문제는 아니지만, 실제 API를 붙인 지금도 그대로 남아 있다.
   * 제대로 고치려면 복습 API(`/review-sessions`) 연동이 필요하고, 그건 이번 작업
   * 범위 밖이라 다음 작업으로 미뤄져 있다. 그 작업이 끝나면 이 fallback을 지운다.
   */
  async startSession(theme?: LearningThemeId) {
    const curriculumId = CURRICULUM_ID_BY_THEME[theme ?? "consonant"];
    const response = problemsResponseSchema.parse(
      await api.post("/learning-sessions", { curriculumId }),
    );
    return toSession(response, theme);
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

  async getHomeSummary(): Promise<HomeSummary> {
    await delay(250);
    return homeSummary;
  },

  async getSkillLevels() {
    await delay(200);
    return skillLevels;
  },
};
