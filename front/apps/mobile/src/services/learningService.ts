import {
  problemsResponseSchema,
  completionResponseSchema,
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
        prompt: `'${p.targetText}'을 점자로 입력하세요`,
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
  /** 새 학습 세션을 만든다. 서버가 문항을 배정해 함께 돌려준다 */
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
