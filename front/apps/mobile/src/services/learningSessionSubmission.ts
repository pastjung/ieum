import type { AttemptInput, CompletionResponse } from "@ieum/contracts";

export type SessionSubmissionOutcome =
  | { kind: "skipped" }
  | { kind: "success"; result: CompletionResponse }
  | { kind: "failed"; error: unknown };

/**
 * `analyzing.tsx`가 일괄 제출에서 무엇을 할지 결정한다.
 *
 * 목 세션(`serverSessionId === null`)이면 서버를 부르지 않는다 — 그 경우 `buildAttempts()`는
 * 항상 빈 배열이고(`sessionStore.test.ts`), 빈 배열로 완료 요청을 보내면 백엔드가 거부한다
 * (`LearningSessionCompletionService.validateAndIndexAttempts`). 이 분기 하나가 화면의
 * 핵심 로직이라 RN 의존성(라우터·zustand·낭독) 없이 따로 뗀다 — `learningSessionCache.ts`의
 * `beginLearningSession`과 같은 이유다.
 */
export async function submitSessionResults(params: {
  serverSessionId: number | null;
  attempts: AttemptInput[];
  completeSession: (sessionId: number, attempts: AttemptInput[]) => Promise<CompletionResponse>;
}): Promise<SessionSubmissionOutcome> {
  const { serverSessionId, attempts, completeSession } = params;
  if (serverSessionId === null) return { kind: "skipped" };
  try {
    const result = await completeSession(serverSessionId, attempts);
    return { kind: "success", result };
  } catch (error) {
    return { kind: "failed", error };
  }
}
