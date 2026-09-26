import type { LearnerCurrentProblem, LiveAnswer } from "@ieum/contracts";
import {
  acceptResult,
  acknowledgeGrade,
  applySnapshot,
  problemAnnouncement,
  SUBMITTED_WAITING_MESSAGE,
  tallyResults,
  WAITING_MESSAGE,
  waitingText,
  type LectureProblem,
  type ProblemPhase,
} from "@/services/lectureProblemFlow";

const deps = { newKey: jest.fn(() => "key-new"), now: jest.fn(() => 1000) };

function problemOf(id: string): LectureProblem {
  return { problemId: id, problemType: "CONSONANT", targetText: "ㄱ", displayText: "초성 ㄱ", cellCount: 1 };
}

function current(id: string, submitted = false): LearnerCurrentProblem {
  return { ...problemOf(id), submitted };
}

function answer(id: string, isCorrect = true): LiveAnswer {
  return {
    attemptId: "900",
    lectureId: "42",
    problemId: id,
    isCorrect,
    score: isCorrect ? 100 : 0,
    attemptedAt: "2026-09-27T01:00:00.000Z",
    cells: [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }],
  };
}

function solving(id: string, key = "key-old"): ProblemPhase {
  return { kind: "solving", problem: problemOf(id), idempotencyKey: key, startedAt: 0 };
}

function graded(id: string): ProblemPhase {
  return { kind: "graded", problem: problemOf(id), result: answer(id) };
}

beforeEach(() => jest.clearAllMocks());

describe("problemAnnouncement", () => {
  it("설계서 문구 그대로 문제를 읽는다", () => {
    expect(problemAnnouncement("초성 ㄱ")).toBe("선생님 문제예요. '초성 ㄱ'을 입력하세요");
  });
});

describe("applySnapshot", () => {
  it("처음 조회에서 문제가 없으면 대기로 가고 기다린다고 알린다", () => {
    expect(applySnapshot(null, null, deps)).toEqual({ phase: { kind: "waiting" }, announcement: WAITING_MESSAGE });
  });

  it("이미 대기 중이면 다시 알리지 않는다", () => {
    const phase: ProblemPhase = { kind: "waiting" };
    expect(applySnapshot(phase, null, deps)).toEqual({ phase, announcement: null });
  });

  it("새 문제가 오면 새 멱등 키와 시작 시각으로 풀이를 시작하고 문제를 읽는다", () => {
    const t = applySnapshot({ kind: "waiting" }, current("7"), deps);

    expect(t.phase).toEqual({ kind: "solving", problem: problemOf("7"), idempotencyKey: "key-new", startedAt: 1000 });
    expect(t.announcement).toBe("선생님 문제예요. '초성 ㄱ'을 입력하세요");
  });

  it("같은 문제를 다시 조회하면 키와 시작 시각을 그대로 둔다 (재시도는 같은 키)", () => {
    const phase = solving("7", "key-old");

    const t = applySnapshot(phase, current("7"), deps);

    expect(t.phase).toBe(phase);
    expect(t.announcement).toBeNull();
    expect(deps.newKey).not.toHaveBeenCalled();
  });

  it("풀던 중 다른 문제가 오면 새 키로 바꾼다", () => {
    const t = applySnapshot(solving("7", "key-old"), current("8"), deps);

    expect(t.phase).toMatchObject({ kind: "solving", problem: { problemId: "8" }, idempotencyKey: "key-new" });
  });

  it("채점 화면에 있는 동안 새 문제가 오면 풀이로 바꾸고 알린다", () => {
    const t = applySnapshot(graded("7"), current("8"), deps);

    expect(t.phase).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
    expect(t.announcement).toBe(problemAnnouncement("초성 ㄱ"));
  });

  it("이미 제출한 현재 문제(재접속)는 제출 대기로 두고 알린다", () => {
    expect(applySnapshot(null, current("7", true), deps)).toEqual({
      phase: { kind: "submitted", problemId: "7" },
      announcement: SUBMITTED_WAITING_MESSAGE,
    });
  });

  it("채점 결과를 보는 중에 같은 문제가 제출됨으로 와도 채점 화면을 유지한다", () => {
    const phase = graded("7");
    expect(applySnapshot(phase, current("7", true), deps)).toEqual({ phase, announcement: null });
  });

  it("한 번 제출한 문제는 늦게 온 미제출 스냅샷으로 되돌리지 않는다", () => {
    const phase: ProblemPhase = { kind: "submitted", problemId: "7" };
    expect(applySnapshot(phase, current("7", false), deps)).toEqual({ phase, announcement: null });
    const shown = graded("7");
    expect(applySnapshot(shown, current("7", false), deps).phase).toBe(shown);
  });

  it("채점 화면에서 문제가 없다는 스냅샷이 와도 결과를 지우지 않는다", () => {
    const phase = graded("7");
    expect(applySnapshot(phase, null, deps).phase).toBe(phase);
  });

  it("풀던 문제가 이미 채점되어 있으면(ALREADY_ANSWERED 뒤 재조회) 제출 대기 대신 채점 화면으로 간다 (M5)", () => {
    const phase = solving("7");
    const result = answer("7", false);

    const t = applySnapshot(phase, current("7", true), deps, result);

    expect(t).toEqual({ phase: { kind: "graded", problem: problemOf("7"), result }, announcement: null });
  });

  it("결과의 문제 id가 지금 풀이 중인 문제와 다르면 그냥 제출 대기로 둔다 (M5)", () => {
    const t = applySnapshot(solving("7"), current("7", true), deps, answer("8", false));

    expect(t).toEqual({ phase: { kind: "submitted", problemId: "7" }, announcement: SUBMITTED_WAITING_MESSAGE });
  });
});

describe("acceptResult", () => {
  it("풀던 문제의 결과면 채점 상태로 바꾼다 (낭독은 채점 화면이 한다)", () => {
    const result = answer("7", false);
    expect(acceptResult(solving("7"), result)).toEqual({
      phase: { kind: "graded", problem: problemOf("7"), result },
      announcement: null,
    });
  });

  it("그 사이 다른 문제로 바뀌었거나 풀이 상태가 아니면 null이다", () => {
    expect(acceptResult(solving("8"), answer("7"))).toBeNull();
    expect(acceptResult({ kind: "waiting" }, answer("7"))).toBeNull();
    expect(acceptResult(null, answer("7"))).toBeNull();
  });
});

describe("acknowledgeGrade", () => {
  it("채점을 확인하면 제출 대기로 돌아가고 알린다", () => {
    expect(acknowledgeGrade(graded("7"))).toEqual({
      phase: { kind: "submitted", problemId: "7" },
      announcement: SUBMITTED_WAITING_MESSAGE,
    });
  });

  it("채점 상태가 아니면 null이다", () => {
    expect(acknowledgeGrade(solving("7"))).toBeNull();
    expect(acknowledgeGrade(null)).toBeNull();
  });
});

describe("waitingText", () => {
  it("제출 대기면 제출 문구, 그 밖에는 대기 문구다", () => {
    expect(waitingText({ kind: "submitted", problemId: "7" })).toBe(SUBMITTED_WAITING_MESSAGE);
    expect(waitingText({ kind: "waiting" })).toBe(WAITING_MESSAGE);
    expect(waitingText(null)).toBe(WAITING_MESSAGE);
  });
});

describe("tallyResults", () => {
  it("낸 문제 수와 맞힌 수를 센다", () => {
    expect(tallyResults({})).toEqual({ total: 0, correct: 0 });
    expect(tallyResults({ "7": true, "8": false, "9": true })).toEqual({ total: 3, correct: 2 });
  });
});
