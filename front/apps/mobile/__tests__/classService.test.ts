import { ApiError } from "@ieum/api-client";
import { api } from "../src/services/apiClient";
import { classService, joinErrorMessage } from "../src/services/classService";

jest.mock("../src/services/apiClient", () => ({
  api: { get: jest.fn(), post: jest.fn(), put: jest.fn() },
}));

const mockedApi = api as unknown as { get: jest.Mock; post: jest.Mock };

const snapshot = (status: string) => ({
  lectureId: "42",
  title: "약자 4회차",
  description: null,
  status,
  scheduledAt: null,
  startedAt: null,
  endedAt: null,
  currentProblem: null,
  createdAt: "2026-09-26T01:00:00.000Z",
  myParticipation: { joinedAt: "2026-09-26T01:00:00.000Z", leftAt: null },
  reviewableCurriculumStages: [],
});

beforeEach(() => jest.resetAllMocks());

describe("classService", () => {
  it("코드로 입장하면 수업 식별자와 상태를 돌려준다", async () => {
    mockedApi.post.mockResolvedValue(snapshot("SCHEDULED"));

    const info = await classService.joinByCode("012345");

    expect(mockedApi.post).toHaveBeenCalledWith("/lecture-participations", { participationCode: "012345" });
    expect(info).toEqual({ lectureId: "42", title: "약자 4회차", status: "SCHEDULED" });
  });

  it("스냅샷을 다시 조회한다", async () => {
    mockedApi.get.mockResolvedValue(snapshot("OPEN"));

    const info = await classService.getLecture("42");

    expect(mockedApi.get).toHaveBeenCalledWith("/lectures/42");
    expect(info.status).toBe("OPEN");
  });

  it("음성 토큰을 발급받는다", async () => {
    mockedApi.post.mockResolvedValue({
      serverUrl: "wss://openvidu.i-eum.site",
      token: "jwt",
      expiresAt: "2026-09-26T01:10:00.000Z",
    });

    const token = await classService.issueMediaToken("42");

    expect(mockedApi.post).toHaveBeenCalledWith("/lectures/42/media-tokens");
    expect(token.token).toBe("jwt");
  });

  it("응답 형식이 계약과 다르면 실패한다", async () => {
    mockedApi.post.mockResolvedValue({ lectureId: 42 });

    await expect(classService.joinByCode("012345")).rejects.toThrow();
  });

  it("입장 실패 사유를 사용자 문구로 바꾼다", () => {
    expect(joinErrorMessage(new ApiError(404, "LECTURE_NOT_JOINABLE", "x"))).toBe(
      "입장할 수 있는 수업이 없어요. 참여 코드를 확인해 주세요.",
    );
    expect(joinErrorMessage(new ApiError(403, "LEARNER_ACCESS_REQUIRED", "x"))).toBe(
      "학습자 계정으로만 수업에 입장할 수 있어요.",
    );
    expect(joinErrorMessage(new ApiError(0, "NETWORK_UNAVAILABLE", "x"))).toBe(
      "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.",
    );
    expect(joinErrorMessage(new Error("?"))).toBe("입장하지 못했어요. 다시 시도해 주세요.");
  });

  it("현재 문제와 내 결과를 조회한다", async () => {
    const currentProblem = {
      problemId: "26578",
      problemType: "CONSONANT",
      targetText: "ㄱ",
      displayText: "초성 ㄱ",
      cellCount: 1,
      submitted: false,
    };
    mockedApi.get.mockResolvedValue({ ...snapshot("OPEN"), currentProblem, myResult: null });

    const state = await classService.getLectureProblem("42");

    expect(mockedApi.get).toHaveBeenCalledWith("/lectures/42");
    expect(state).toEqual({ status: "OPEN", currentProblem, myResult: null });
  });

  it("답안을 멱등 키와 함께 제출하고 서버 채점 결과를 돌려준다", async () => {
    const answer = {
      attemptId: "900",
      lectureId: "42",
      problemId: "26578",
      isCorrect: true,
      score: 100,
      attemptedAt: "2026-09-27T01:00:00.000Z",
      cells: [{ cellIndex: 1, inputPattern: 8, expectedPattern: 8, diffMask: 0, isCorrect: true }],
    };
    mockedApi.post.mockResolvedValue(answer);

    const result = await classService.submitLiveAnswer("42", "26578", { cells: [8], durationMs: 1900 }, "key-1");

    expect(mockedApi.post).toHaveBeenCalledWith(
      "/lectures/42/problems/26578/answers",
      { cells: [8], durationMs: 1900 },
      { headers: { "Idempotency-Key": "key-1" } },
    );
    expect(result).toEqual(answer);
  });

  it("계약을 벗어난 답안은 보내지 않는다", async () => {
    await expect(
      classService.submitLiveAnswer("42", "26578", { cells: [], durationMs: 0 }, "key-1"),
    ).rejects.toThrow();
    expect(mockedApi.post).not.toHaveBeenCalled();
  });
});
