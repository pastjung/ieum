import type { AttemptAnalysisResponse } from "@ieum/contracts";
import { ApiError } from "@ieum/api-client";
import { describeAuthError } from "./authErrors";

/** 이 안에서만 재시도한다. 초과하면 화면이 처음부터 다시 시작하도록 안내한다 */
export const MAX_ATTEMPT_TRIES = 3;

/**
 * 화면이 문항 제출 한 번에서 무엇을 할지 판단하는 결과.
 *
 * 갈래가 넷인 이유: `already`를 `failed`와 합치면 화면이 오류로 다뤄 이미 끝난 문항에서
 * 사용자가 막힌다(아래 설명). `exhausted`를 `failed`와 합치면 화면이 무한히 "다시 보내기"를
 * 보여줘, 12칸 초과처럼 재시도해도 절대 성공할 수 없는 상황에서 사용자를 가둔다.
 */
export type AttemptOutcome =
  | { kind: "analyzed"; result: AttemptAnalysisResponse }
  | { kind: "already" }
  | { kind: "failed"; message: string; attempt: number }
  | { kind: "exhausted"; message: string };

/**
 * 화면이 재시도할 때 다시 시작하라고 안내하는 문구. `buildRetryFailureMessage`
 * (`learningSessionSubmission.ts`)와 같은 이유로 화면과 낭독이 하나의 문자열을 공유한다.
 */
const EXHAUSTED_MESSAGE = "여러 번 시도했지만 계속 실패했어요. 처음부터 다시 시작해 주세요.";

const RETRY_ORDINAL_KO: Record<number, string> = {
  2: "두 번째",
  3: "세 번째",
  4: "네 번째",
  5: "다섯 번째",
};

/**
 * 실패 문구에 시도 횟수를 담는다.
 *
 * `a11y/speech.ts`의 `speak()`는 같은 문구가 1.5초 안에 다시 들어오면 조용히 무시한다.
 * 재시도가 같은 원인으로 다시 실패하면 문구를 고정한 채로는 두 번째 실패의 안내가
 * 음성으로만 듣는 사용자에게 씹혀 "다시 보내기를 눌렀는데 아무 일도 안 일어난 것"처럼
 * 보인다. 시도 횟수를 문구에 담아 매번 다른 문자열이 되게 한다 —
 * `learningSessionSubmission.ts`의 `buildRetryFailureMessage`와 같은 방식이다.
 */
function buildAttemptFailureMessage(attempt: number, cause: string): string {
  if (attempt <= 1) return `${cause} 다시 보내기를 누르면 한 번 더 시도해요.`;
  const ordinal = RETRY_ORDINAL_KO[attempt] ?? `${attempt}번째`;
  return `${cause} ${ordinal} 시도에도 안 됐어요. 다시 보내기를 눌러 주세요.`;
}

/**
 * 문항 하나를 제출하고 결과에 따라 화면이 할 일을 결정한다.
 *
 * `analyze`를 인자로 받는 이유는 테스트에서 네트워크 없이 부르기 위해서다
 * (`learningSessionSubmission.ts`의 `submitSessionResults`와 같은 방식). 화면은
 * `(sessionId, problemSessionId, inputPatterns) => learningService.analyzeAttempt(...)`를
 * 넘기면 된다.
 *
 * `LEARNING_PROBLEM_ALREADY_ANALYZED`를 오류로 다루지 않는 이유: 앱이 응답을 못 받았지만
 * 서버는 이미 채점·분석을 끝낸 상태다(응답 유실, 앱 재시작). 오류로 다루면 사용자는
 * 이미 끝난 문항에서 영영 막힌다. 다만 서버가 이 경로로는 분석 결과를 돌려주지 않으므로
 * 그 문항의 피드백은 만들 수 없다 — 화면은 "이미 확인한 문제예요"라고만 알리고 다음
 * 문항으로 넘어가야 한다.
 */
export async function submitAttempt(params: {
  analyze: (
    sessionId: number,
    problemSessionId: number,
    inputPatterns: number[],
  ) => Promise<AttemptAnalysisResponse>;
  sessionId: number;
  problemSessionId: number;
  inputPatterns: number[];
  attempt: number;
}): Promise<AttemptOutcome> {
  const { analyze, sessionId, problemSessionId, inputPatterns, attempt } = params;
  try {
    const result = await analyze(sessionId, problemSessionId, inputPatterns);
    return { kind: "analyzed", result };
  } catch (error) {
    if (error instanceof ApiError && error.code === "LEARNING_PROBLEM_ALREADY_ANALYZED") {
      return { kind: "already" };
    }
    if (attempt >= MAX_ATTEMPT_TRIES) {
      return { kind: "exhausted", message: EXHAUSTED_MESSAGE };
    }
    return {
      kind: "failed",
      message: buildAttemptFailureMessage(attempt, describeAuthError(error)),
      attempt,
    };
  }
}
