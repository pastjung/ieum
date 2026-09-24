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

type SubmitParams = Parameters<typeof submitSessionResults>[0];

/**
 * `submitSessionResults`를 감싸, 같은 서버 세션에 대해 이미 진행 중인 제출이 있으면
 * 새로 부르지 않고 그 결과에 올라탄다.
 *
 * `analyzing.tsx`는 뒤로 가기를 막지 않는다(`session/_layout.tsx`가 없어 스와이프 백·
 * 하드웨어 백이 모두 열려 있다). 사용자가 제출 중에 화면을 나갔다가 같은 흐름을 다시
 * 타면(`grade.tsx`가 완료된 세션을 다시 이 화면으로 보낸다) 화면이 다시 마운트되며
 * `submit()`이 또 실행된다 — 이때 첫 요청이 아직 응답을 못 받은 채로 두 번째
 * `completeSession`이 나가면, 백엔드는 이미 완료된 세션이라 거부한다
 * (`LearningSessionCompletionService.validateAndIndexAttempts`) — 방금 성공한 제출을
 * 사용자가 실패로 보게 된다. 같은 이유로 재시도 버튼이 진행 중에 또 눌려도 두 번째
 * 요청이 나가면 안 된다.
 *
 * 이 함수가 만드는 클로저(반환값)를 모듈 스코프에 딱 하나만 만들어 써야 위 상황을
 * 막는다 — 컴포넌트의 `useRef`는 인스턴스에 묶여 재마운트되면 초기화되므로 두 인스턴스에
 * 걸친 "같은 제출이 아직 진행 중"이라는 사실을 표현할 수 없다.
 */
interface InFlightEntry {
  serverSessionId: number | null;
  promise: Promise<SessionSubmissionOutcome>;
}

export function createSubmissionDeduper(): (params: SubmitParams) => Promise<SessionSubmissionOutcome> {
  let inFlight: InFlightEntry | null = null;

  return (params) => {
    if (inFlight !== null && inFlight.serverSessionId === params.serverSessionId) {
      return inFlight.promise;
    }
    // `entry`를 먼저 만들고 그 참조를 클로저로 캡처한다 — `finally`는 자신이 넣은 entry일
    // 때만 지워야 한다. 그냥 `inFlight = null`로 지우면: 세션 A가 진행 중일 때 세션 B로
    // 넘어가 `inFlight`가 B로 바뀐 뒤, 뒤늦게 도착한 A의 응답이 B의 entry를 지워 버린다.
    // 그러면 B가 아직 진행 중인데도 가드가 비어, B를 다시 제출하면 completeSession이
    // 두 번 나간다 — 이 함수가 애초에 막으려던 버그가 세션을 가로질러 되살아난다.
    const entry: InFlightEntry = {
      serverSessionId: params.serverSessionId,
      promise: submitSessionResults(params).finally(() => {
        if (inFlight === entry) inFlight = null;
      }),
    };
    inFlight = entry;
    return entry.promise;
  };
}

const RETRY_ORDINAL_KO: Record<number, string> = {
  2: "두 번째",
  3: "세 번째",
  4: "네 번째",
  5: "다섯 번째",
};

/**
 * 실패 안내를 화면 텍스트와 낭독이 함께 쓸 하나의 문자열로 만든다.
 *
 * 화면과 낭독이 각자 다른 문자열을 조합하면 안 된다 — 다르면 사용자가 어느 쪽을 믿어야
 * 할지 알 수 없다. 그래서 화면 컴포넌트가 조합하지 않고 여기서 하나로 만들어 그대로
 * 화면에도 쓰고 `announce()`에도 넘긴다.
 *
 * 재시도가 같은 오류로 다시 실패하면 문구가 그대로 반복된다. `speak()`(`a11y/speech.ts`)는
 * 같은 문구가 1.5초 안에 다시 들어오면 조용히 무시하므로, 문구를 고정하면 두 번째 실패의
 * 안내가 음성으로만 쓰는 사용자에게 씹힌다 — "다시 보내기를 눌렀는데 아무 일도 안 일어난
 * 것"처럼 보인다(로그인 실패가 `announce()` 중복 제거에 걸려 안 보였던 것과 같은 종류의
 * 버그). 시도 횟수를 문구에 담아 실패할 때마다 다른 문자열이 되게 한다 — 중복 제거를
 * 억지로 뚫는 대신, 문구 자체가 사용자에게 실제 정보(몇 번째 시도인지)를 준다.
 */
export function buildRetryFailureMessage(attempt: number, cause: string): string {
  if (attempt <= 1) return `${cause} 다시 보내기를 누르면 한 번 더 시도해요.`;
  const ordinal = RETRY_ORDINAL_KO[attempt] ?? `${attempt}번째`;
  return `${cause} ${ordinal} 시도에도 안 됐어요. 다시 보내기를 눌러 주세요.`;
}

/**
 * `analyzing.tsx`의 대기 화면 문구. 화면과 낭독이 같은 말을 하도록 한 곳에 둔다
 * (`buildRetryFailureMessage`와 같은 이유).
 *
 * **"앱을 닫으면 처음부터 다시 풀어야 한다"가 핵심이다.** 이어하기가 없고(설계 9절) 답안은
 * 스토어 메모리에만 있어, 여기서 앱을 닫으면 12문항이 전부 사라진다. 행동을 바꿔야 하는
 * 정보이므로 화면에만 두면 안 된다 — 이 앱은 낭독이 주 출력이다.
 *
 * `speech`는 `headline`과 `detail`을 그대로 이은 문장이다. 줄바꿈만 빠진다.
 */
export const ANALYZING_WAITING_COPY = {
  headline: "학습 완료!\n결과를 분석 중이에요",
  detail: "잠시만 기다려 주세요.\n앱을 닫으면 처음부터 다시 풀어야 해요.",
  speech: "학습 완료! 결과를 분석 중이에요. 잠시만 기다려 주세요. 앱을 닫으면 처음부터 다시 풀어야 해요.",
} as const;
