import type { CompletionResponse } from "@ieum/contracts";
import {
  submitSessionResults,
  createSubmissionDeduper,
  buildRetryFailureMessage,
  ANALYZING_WAITING_COPY,
} from "../src/services/learningSessionSubmission";

/**
 * `analyzing.tsx`의 제출 분기(`submitSessionResults`)를 직접 부른다. `beginLearningSession`
 * 테스트와 같은 이유 — 화면이 이 함수 하나만 불러 결과를 판단하므로, 이 함수의 동작이
 * 화면의 동작이다.
 */
describe("submitSessionResults", () => {
  it("목 세션(serverSessionId 없음)이면 서버를 부르지 않고 건너뛴다", async () => {
    const completeSession = jest.fn();

    const outcome = await submitSessionResults({
      serverSessionId: null,
      attempts: [],
      completeSession,
    });

    expect(outcome).toEqual({ kind: "skipped" });
    expect(completeSession).not.toHaveBeenCalled();
  });

  it("성공하면 응답을 그대로 담아 돌려준다", async () => {
    const result: CompletionResponse = {
      sessionId: 12,
      completedAt: "2026-09-24T00:00:00Z",
      totalProblemCount: 2,
      correctProblemCount: 1,
      problemResults: [],
    };
    const completeSession = jest.fn().mockResolvedValue(result);
    const attempts = [{ problemSessionId: 101, inputPatterns: [1] }];

    const outcome = await submitSessionResults({ serverSessionId: 12, attempts, completeSession });

    expect(outcome).toEqual({ kind: "success", result });
    expect(completeSession).toHaveBeenCalledWith(12, attempts);
  });

  it("실패하면(네트워크·검증 거부 등) 오류를 담아 돌려주고 던지지 않는다", async () => {
    const error = new Error("network down");
    const completeSession = jest.fn().mockRejectedValue(error);

    const outcome = await submitSessionResults({ serverSessionId: 12, attempts: [], completeSession });

    expect(outcome).toEqual({ kind: "failed", error });
  });
});

const completionOf = (correct: number): CompletionResponse => ({
  sessionId: 1,
  completedAt: "2026-09-24T00:00:00Z",
  totalProblemCount: 1,
  correctProblemCount: correct,
  problemResults: [],
});

/**
 * 리뷰 Important 재검증 — `analyzing.tsx`가 뒤로 가기로 언마운트됐다가 곧바로 다시
 * 마운트돼도(또는 재시도 버튼이 진행 중에 또 눌려도) 같은 서버 세션에 completeSession이
 * 두 번 나가지 않아야 한다. 두 번째가 나가면 백엔드가 이미 완료된 세션이라 거부해
 * 방금 성공한 제출을 사용자가 실패로 보게 된다
 * (`LearningSessionCompletionService.validateAndIndexAttempts`).
 */
describe("createSubmissionDeduper", () => {
  it("같은 세션에 대해 이미 진행 중이면 completeSession을 다시 부르지 않고 같은 결과에 올라탄다", async () => {
    const dedupedSubmit = createSubmissionDeduper();
    let resolveFirst: (r: CompletionResponse) => void = () => {};
    const completeSession = jest.fn(
      () => new Promise<CompletionResponse>((resolve) => { resolveFirst = resolve; }),
    );

    const first = dedupedSubmit({ serverSessionId: 1, attempts: [], completeSession });
    const second = dedupedSubmit({ serverSessionId: 1, attempts: [], completeSession });

    expect(completeSession).toHaveBeenCalledTimes(1);

    const result = completionOf(1);
    resolveFirst(result);

    await expect(first).resolves.toEqual({ kind: "success", result });
    await expect(second).resolves.toEqual({ kind: "success", result });
  });

  it("이전 제출이 끝난 뒤에는(성공이든 실패든) 새 요청을 보낸다", async () => {
    const dedupedSubmit = createSubmissionDeduper();
    const completeSession = jest
      .fn()
      .mockRejectedValueOnce(new Error("첫 시도 실패"))
      .mockResolvedValueOnce(completionOf(1));

    const firstOutcome = await dedupedSubmit({ serverSessionId: 1, attempts: [], completeSession });
    const secondOutcome = await dedupedSubmit({ serverSessionId: 1, attempts: [], completeSession });

    expect(completeSession).toHaveBeenCalledTimes(2);
    expect(firstOutcome.kind).toBe("failed");
    expect(secondOutcome).toEqual({ kind: "success", result: completionOf(1) });
  });

  it("다른 세션이면 이전이 진행 중이어도 새 요청을 보낸다", async () => {
    const dedupedSubmit = createSubmissionDeduper();
    const resolvers = new Map<number, (r: CompletionResponse) => void>();
    const completeSession = jest.fn(
      (sessionId: number) =>
        new Promise<CompletionResponse>((resolve) => {
          resolvers.set(sessionId, resolve);
        }),
    );

    const forSession1 = dedupedSubmit({ serverSessionId: 1, attempts: [], completeSession });
    const forSession2 = dedupedSubmit({ serverSessionId: 2, attempts: [], completeSession });

    expect(completeSession).toHaveBeenCalledTimes(2);
    resolvers.get(1)!(completionOf(1));
    resolvers.get(2)!(completionOf(1));
    await forSession1;
    await forSession2;
  });

  /**
   * 리뷰 P1 재검증 — 정리하는 쪽이 "자기 entry"인지 확인하지 않으면, 먼저 시작한
   * 세션(A)의 뒤늦은 응답이 나중에 시작한 세션(B)의 진행 중 표시를 지워 버린다. 그러면
   * B가 아직 응답을 못 받았는데도 B로 재진입 시 completeSession이 다시 나간다 — 이
   * 함수가 애초에 막으려던 중복 제출이 세션을 가로질러 되살아난다.
   */
  it("먼저 시작한 세션(A)의 응답이 늦게 도착해도, 아직 진행 중인 다른 세션(B)의 표시를 지우지 않는다", async () => {
    const dedupedSubmit = createSubmissionDeduper();
    const resolvers = new Map<number, (r: CompletionResponse) => void>();
    const completeSession = jest.fn(
      (sessionId: number) =>
        new Promise<CompletionResponse>((resolve) => {
          resolvers.set(sessionId, resolve);
        }),
    );

    const forSession1 = dedupedSubmit({ serverSessionId: 1, attempts: [], completeSession }); // A 시작
    const forSession2 = dedupedSubmit({ serverSessionId: 2, attempts: [], completeSession }); // A 진행 중에 B 시작
    expect(completeSession).toHaveBeenCalledTimes(2);

    // A의 응답이 뒤늦게 도착한다 — 이때 B의 진행 중 표시가 잘못 지워지면 안 된다.
    // `forSession1`을 끝까지 기다려야(단순히 마이크로태스크 한 틱을 주는 것으로는
    // 부족하다 — `submitSessionResults`의 await·반환과 `.finally` 콜백까지 여러 틱이
    // 걸린다) A 쪽 `finally` 콜백이 실제로 실행된 뒤 상태를 확인할 수 있다.
    resolvers.get(1)!(completionOf(1));
    await forSession1;

    // B는 아직 응답 전이다. 이 시점에 B로 다시 들어와도(예: 화면 재진입) 새 요청이
    // 나가면 안 된다 — 나가면 세 번째 호출이 찍힌다
    const forSession2Again = dedupedSubmit({ serverSessionId: 2, attempts: [], completeSession });
    expect(completeSession).toHaveBeenCalledTimes(2);

    resolvers.get(2)!(completionOf(1));
    await forSession2;
    await forSession2Again;
  });
});

/**
 * 리뷰 Minor 재검증 — 화면 문구와 낭독 문구가 같은 문자열이어야 하고(다르면 어느 쪽을
 * 믿어야 할지 알 수 없다), 재시도 실패마다 문구가 달라야 한다. `speak()`는 같은 문구가
 * 1.5초 안에 다시 들어오면 무시하므로(`a11y/speech.ts`), 문구가 고정돼 있으면 재시도의
 * 두 번째 실패 안내가 음성으로만 쓰는 사용자에게 조용히 씹힌다.
 */
describe("buildRetryFailureMessage", () => {
  it("첫 시도 실패는 원인과 다시 보내기 안내를 담는다", () => {
    expect(buildRetryFailureMessage(1, "네트워크에 연결할 수 없어요.")).toBe(
      "네트워크에 연결할 수 없어요. 다시 보내기를 누르면 한 번 더 시도해요.",
    );
  });

  it("같은 원인이라도 시도 횟수가 다르면 문구가 달라진다(중복 낭독 제거에 걸리지 않는다)", () => {
    const cause = "네트워크에 연결할 수 없어요.";
    const first = buildRetryFailureMessage(1, cause);
    const second = buildRetryFailureMessage(2, cause);
    const third = buildRetryFailureMessage(3, cause);

    expect(second).not.toBe(first);
    expect(third).not.toBe(second);
    expect(second).toContain("두 번째");
    expect(third).toContain("세 번째");
  });

  it("매핑에 없는 큰 시도 횟수도 숫자로 문구를 만든다", () => {
    expect(buildRetryFailureMessage(9, "원인")).toBe("원인 9번째 시도에도 안 됐어요. 다시 보내기를 눌러 주세요.");
  });
});

describe("분석 중 대기 화면 문구", () => {
  const visibleLines = [
    ...ANALYZING_WAITING_COPY.headline.split("\n"),
    ...ANALYZING_WAITING_COPY.detail.split("\n"),
  ];

  it("화면에 보이는 모든 줄이 낭독 문구에도 들어 있다", () => {
    // 화면과 낭독이 갈라지면 사용자는 어느 쪽을 믿어야 할지 알 수 없다 (설계 8절).
    // 문장 끝 문장부호만 낭독 쪽에서 다듬는다
    for (const line of visibleLines) {
      expect(ANALYZING_WAITING_COPY.speech).toContain(line.replace(/[.!?]$/, ""));
    }
  });

  it("앱을 닫으면 답안이 사라진다는 사실을 낭독한다", () => {
    // 이어하기가 없어(설계 9절) 여기서 앱을 닫으면 12문항이 전부 사라진다.
    // 행동을 바꿔야 하는 정보라 화면에만 두면 안 된다 — 이 앱은 낭독이 주 출력이다
    expect(ANALYZING_WAITING_COPY.speech).toContain("앱을 닫으면 처음부터 다시 풀어야 해요");
    expect(ANALYZING_WAITING_COPY.detail).toContain("앱을 닫으면 처음부터 다시 풀어야 해요");
  });
});
