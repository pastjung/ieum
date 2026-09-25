import type { QueryClient } from "@tanstack/react-query";
import type { LearningSession } from "@ieum/contracts";
import type { ServerSessionMeta, SessionKind } from "@/stores/sessionStore";

/**
 * `intro.tsx`의 세션 조회 쿼리 키. 조회 쪽과 캐시 제거 쪽이 같은 키를 쓰도록 여기 한 곳에
 * 둔다 — 어긋나면 "시작하기"에서 지운 캐시와 다음 조회가 쓰는 캐시가 달라져, 완료된 세션이
 * 재사용되고 서버가 재제출을 거부하는 문제가 그대로 되살아난다.
 *
 * `learningService.ts`(API 접근 계층)에 두지 않은 이유: 이 키는 화면(intro)의 캐시
 * 정체성이지 API 호출의 일부가 아니다. API 계층에 화면 캐시 키가 얹히면 계층이 어색해진다.
 */
/**
 * intro 화면이 만들려는 세션의 정체성.
 *
 * 복습은 주제를 고르지 않는다 — 서버가 내 기록에서 약한 요소를 골라 출제한다
 * (`POST /review-sessions`). 그래서 주제 하나로는 표현할 수 없다.
 */
export type SessionRequest =
  | { kind: "stage"; curriculumId: number; title: string }
  | { kind: "review" }
  | { kind: "resume"; sessionId: number };

export function learningSessionQueryKey(request: SessionRequest) {
  switch (request.kind) {
    case "review":
      return ["learningSession", "review"] as const;
    // 세션마다 다른 키를 쓴다. 하나로 묶으면 앞서 이어받은 세션이 캐시에 남아, 다른
    // 세션을 이어받아도 그것이 나온다
    case "resume":
      return ["learningSession", "resume", request.sessionId] as const;
    default:
      return ["learningSession", "stage", request.curriculumId] as const;
  }
}

/**
 * 세션 소유권이 스토어로 넘어가는 지점. `intro.tsx`의 "시작하기"가 이 함수 하나만 부른다.
 *
 * `removeQueries`를 부르지 않으면 완료된 세션이 되살아난다 — `staleTime`/`gcTime`이
 * Infinity인 쿼리는 `QueryClient`가 앱 수명 동안 하나이므로, 화면을 나갔다가 같은
 * 주제로 다시 들어와도 캐시가 그대로 남는다. 그 상태로 다시 제출하면 백엔드가 완료된
 * 배정을 거부한다(`LearningSessionCompletionService.validateAndIndexAttempts` →
 * `INVALID_COMPLETION_REQUEST`).
 */
export function beginLearningSession(
  queryClient: QueryClient,
  request: SessionRequest,
  data: { session: LearningSession; meta: ServerSessionMeta },
  start: (session: LearningSession, kind: SessionKind, meta: ServerSessionMeta) => void,
): void {
  start(data.session, "free", data.meta);
  queryClient.removeQueries({ queryKey: learningSessionQueryKey(request) });
}
