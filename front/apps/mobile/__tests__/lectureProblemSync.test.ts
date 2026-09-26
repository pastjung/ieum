import { ApiError } from "@ieum/api-client";
import type { LearnerCurrentProblem, LiveAnswer } from "@ieum/contracts";
import { announce } from "@/a11y";
import { classService } from "@/services/classService";
import { newIdempotencyKey } from "@/services/idempotencyKey";
import {
  PROBLEM_CHANGED_MESSAGE,
  problemAnnouncement,
  SUBMIT_CLOSED_MESSAGE,
  SUBMIT_FAILED_MESSAGE,
  SUBMITTED_WAITING_MESSAGE,
  WAITING_MESSAGE,
} from "@/services/lectureProblemFlow";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";

jest.mock("@/a11y", () => ({ announce: jest.fn() }));
jest.mock("@/services/classService", () => ({
  classService: { getLectureProblem: jest.fn(), submitLiveAnswer: jest.fn() },
}));
jest.mock("@/services/idempotencyKey", () => ({ newIdempotencyKey: jest.fn() }));

const mockedClass = classService as unknown as { getLectureProblem: jest.Mock; submitLiveAnswer: jest.Mock };
const mockedAnnounce = announce as unknown as jest.Mock;
const mockedKey = newIdempotencyKey as unknown as jest.Mock;

function current(id: string, submitted = false): LearnerCurrentProblem {
  return { problemId: id, problemType: "CONSONANT", targetText: "ㄱ", displayText: "초성 ㄱ", cellCount: 1, submitted };
}

function snapshot(currentProblem: LearnerCurrentProblem | null, myResult: LiveAnswer | null = null) {
  return { status: "OPEN", currentProblem, myResult };
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

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function flushMicrotasks(times = 10): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

const phase = () => useLectureProblemStore.getState().phase;

/** 풀이 상태로 만든 뒤 낭독 기록을 비운다 */
async function startSolving(id = "7"): Promise<void> {
  mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current(id)));
  await lectureProblemSync.refresh();
  mockedAnnounce.mockClear();
}

let nowSpy: jest.SpyInstance | null = null;

beforeEach(() => {
  jest.resetAllMocks();
  let n = 0;
  mockedKey.mockImplementation(() => `key-${++n}`);
  lectureProblemSync.reset();
  lectureProblemSync.begin("42");
});

afterEach(() => {
  nowSpy?.mockRestore();
  nowSpy = null;
});

describe("lectureProblemSync 조회", () => {
  it("문제가 없으면 대기로 두고 기다린다고 알린다", async () => {
    mockedClass.getLectureProblem.mockResolvedValue(snapshot(null));

    await lectureProblemSync.refresh();

    expect(mockedClass.getLectureProblem).toHaveBeenCalledWith("42");
    expect(phase()).toEqual({ kind: "waiting" });
    expect(mockedAnnounce).toHaveBeenCalledWith(WAITING_MESSAGE);
  });

  it("문제가 있으면 풀이로 바꾸고 문제를 읽는다", async () => {
    mockedClass.getLectureProblem.mockResolvedValue(snapshot(current("7")));

    await lectureProblemSync.refresh();

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "7" }, idempotencyKey: "key-1" });
    expect(mockedAnnounce).toHaveBeenCalledWith(problemAnnouncement("초성 ㄱ"));
  });

  it("겹친 조회는 마지막 요청의 응답만 반영한다", async () => {
    const first = deferred<ReturnType<typeof snapshot>>();
    mockedClass.getLectureProblem
      .mockImplementationOnce(() => first.promise)
      .mockResolvedValueOnce(snapshot(current("8")));

    const r1 = lectureProblemSync.refresh();
    await lectureProblemSync.refresh();
    first.resolve(snapshot(current("7")));
    await r1;

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
    expect(mockedAnnounce).toHaveBeenCalledTimes(1);
  });

  it("퇴장(stop) 뒤에 도착한 조회 응답은 버린다", async () => {
    const gate = deferred<ReturnType<typeof snapshot>>();
    mockedClass.getLectureProblem.mockImplementationOnce(() => gate.promise);

    const r = lectureProblemSync.refresh();
    lectureProblemSync.stop();
    gate.resolve(snapshot(current("7")));
    await r;

    expect(phase()).toBeNull();
    expect(mockedAnnounce).not.toHaveBeenCalled();
  });

  it("퇴장한 뒤에는 조회하지 않는다", async () => {
    lectureProblemSync.stop();

    await lectureProblemSync.refresh();

    expect(mockedClass.getLectureProblem).not.toHaveBeenCalled();
  });

  it("첫 조회가 실패하면 대기로 두고 안내한다", async () => {
    mockedClass.getLectureProblem.mockRejectedValue(new ApiError(0, "NETWORK_UNAVAILABLE", "x"));

    await lectureProblemSync.refresh();

    expect(phase()).toEqual({ kind: "waiting" });
    expect(mockedAnnounce).toHaveBeenCalledWith(WAITING_MESSAGE);
  });

  it("풀던 중 조회가 실패하면 상태를 그대로 둔다", async () => {
    await startSolving("7");
    mockedClass.getLectureProblem.mockRejectedValueOnce(new ApiError(0, "NETWORK_UNAVAILABLE", "x"));

    await lectureProblemSync.refresh();

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "7" } });
    expect(mockedAnnounce).not.toHaveBeenCalled();
  });

  it("이미 낸 현재 문제면 제출 대기로 두고 내 결과를 집계에 넣는다 (재접속)", async () => {
    mockedClass.getLectureProblem.mockResolvedValue(snapshot(current("7", true), answer("7", false)));

    await lectureProblemSync.refresh();

    expect(phase()).toEqual({ kind: "submitted", problemId: "7" });
    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMITTED_WAITING_MESSAGE);
    expect(useLectureProblemStore.getState().results).toEqual({ "7": false });
  });

  it("같은 수업에 다시 들어오면 결과를 두고, 다른 수업이면 비운다", () => {
    useLectureProblemStore.getState().set({ results: { "7": true } });

    lectureProblemSync.begin("42");
    expect(useLectureProblemStore.getState()).toMatchObject({ lectureId: "42", active: true, phase: null, results: { "7": true } });

    lectureProblemSync.begin("43");
    expect(useLectureProblemStore.getState()).toMatchObject({ lectureId: "43", active: true, phase: null, results: {} });
  });
});

describe("lectureProblemSync 제출", () => {
  it("풀던 문제에 멱등 키·칸·걸린 시간을 보내고 채점 상태로 바꾼다", async () => {
    nowSpy = jest.spyOn(Date, "now").mockReturnValue(1000);
    await startSolving("7");
    nowSpy.mockReturnValue(2900);
    mockedClass.submitLiveAnswer.mockResolvedValue(answer("7", true));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("graded");

    expect(mockedClass.submitLiveAnswer).toHaveBeenCalledWith("42", "7", { cells: [8], durationMs: 1900 }, "key-1");
    expect(phase()).toMatchObject({ kind: "graded", problem: { problemId: "7" }, result: { isCorrect: true } });
    expect(useLectureProblemStore.getState().results).toEqual({ "7": true });
  });

  it("실패 뒤 다시 내면 같은 멱등 키를 쓴다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer
      .mockRejectedValueOnce(new ApiError(0, "NETWORK_UNAVAILABLE", "x"))
      .mockResolvedValueOnce(answer("7"));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("failed");
    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMIT_FAILED_MESSAGE);
    expect(phase()).toMatchObject({ kind: "solving" });

    await expect(lectureProblemSync.submit([8])).resolves.toBe("graded");
    expect(mockedClass.submitLiveAnswer.mock.calls[0]![3]).toBe("key-1");
    expect(mockedClass.submitLiveAnswer.mock.calls[1]![3]).toBe("key-1");
  });

  it("새 문제는 새 멱등 키를 받는다", async () => {
    await startSolving("7");
    await startSolving("8");

    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" }, idempotencyKey: "key-2" });
  });

  it("문제가 바뀌었으면(409 NOT_CURRENT) 알리고 다시 조회해 새 문제로 간다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockRejectedValue(new ApiError(409, "LECTURE_PROBLEM_NOT_CURRENT", "x"));
    mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current("8")));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("changed");
    await flushMicrotasks();

    expect(mockedAnnounce.mock.calls[0]![0]).toBe(PROBLEM_CHANGED_MESSAGE);
    expect(mockedAnnounce).toHaveBeenLastCalledWith(problemAnnouncement("초성 ㄱ"));
    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
  });

  it("다른 답을 이미 냈으면(409 ALREADY_ANSWERED) 다시 조회해 제출 대기로 둔다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockRejectedValue(new ApiError(409, "LECTURE_PROBLEM_ALREADY_ANSWERED", "x"));
    mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current("7", true), answer("7", false)));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("changed");
    await flushMicrotasks();

    expect(phase()).toEqual({ kind: "submitted", problemId: "7" });
    expect(mockedAnnounce).toHaveBeenLastCalledWith(SUBMITTED_WAITING_MESSAGE);
  });

  it("수업이 닫혔으면(409 INVALID_LECTURE_STATE) 알리고 끝낸다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockRejectedValue(new ApiError(409, "INVALID_LECTURE_STATE", "x"));

    await expect(lectureProblemSync.submit([8])).resolves.toBe("closed");

    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMIT_CLOSED_MESSAGE);
  });

  it("제출 중에 겹쳐 누르면 나중 요청은 무시한다", async () => {
    await startSolving("7");
    const gate = deferred<LiveAnswer>();
    mockedClass.submitLiveAnswer.mockImplementationOnce(() => gate.promise);

    const first = lectureProblemSync.submit([8]);
    await expect(lectureProblemSync.submit([8])).resolves.toBe("ignored");
    gate.resolve(answer("7"));
    await first;

    expect(mockedClass.submitLiveAnswer).toHaveBeenCalledTimes(1);
  });

  it("풀이 상태가 아니면 보내지 않는다", async () => {
    await expect(lectureProblemSync.submit([8])).resolves.toBe("ignored");
    expect(mockedClass.submitLiveAnswer).not.toHaveBeenCalled();
  });

  it("제출 전에 떠난 조회 응답이 채점 결과를 덮지 않는다", async () => {
    await startSolving("7");
    const gate = deferred<ReturnType<typeof snapshot>>();
    mockedClass.getLectureProblem.mockImplementationOnce(() => gate.promise);
    mockedClass.submitLiveAnswer.mockResolvedValue(answer("7"));

    const r = lectureProblemSync.refresh();
    await lectureProblemSync.submit([8]);
    gate.resolve(snapshot(current("7", false)));
    await r;

    expect(phase()).toMatchObject({ kind: "graded" });
  });

  it("채점을 기다리는 사이 다음 문제가 오면 결과는 집계만 하고 새 문제를 유지한다", async () => {
    await startSolving("7");
    const gate = deferred<LiveAnswer>();
    mockedClass.submitLiveAnswer.mockImplementationOnce(() => gate.promise);
    mockedClass.getLectureProblem.mockResolvedValueOnce(snapshot(current("8")));

    const submitting = lectureProblemSync.submit([8]);
    await lectureProblemSync.refresh(); // 출제 신호로 온 조회 — 제출 뒤에 떠났으니 유효하다
    gate.resolve(answer("7", false));

    await expect(submitting).resolves.toBe("ignored");
    expect(phase()).toMatchObject({ kind: "solving", problem: { problemId: "8" } });
    expect(useLectureProblemStore.getState().results).toEqual({ "7": false });
  });

  it("퇴장한 뒤 도착한 제출 응답은 반영하지 않는다", async () => {
    await startSolving("7");
    const gate = deferred<LiveAnswer>();
    mockedClass.submitLiveAnswer.mockImplementationOnce(() => gate.promise);

    const submitting = lectureProblemSync.submit([8]);
    lectureProblemSync.stop();
    gate.resolve(answer("7"));

    await expect(submitting).resolves.toBe("ignored");
    expect(phase()).toMatchObject({ kind: "solving" });
  });

  it("채점을 확인하면 제출 대기로 돌아가고 알린다", async () => {
    await startSolving("7");
    mockedClass.submitLiveAnswer.mockResolvedValue(answer("7"));
    await lectureProblemSync.submit([8]);
    mockedAnnounce.mockClear();

    lectureProblemSync.acknowledgeGrade();

    expect(phase()).toEqual({ kind: "submitted", problemId: "7" });
    expect(mockedAnnounce).toHaveBeenCalledWith(SUBMITTED_WAITING_MESSAGE);
  });
});
