import { learnerLectureSchema, liveAnswerRequestSchema, liveAnswerSchema, mediaTokenSchema } from "@ieum/contracts";

const learner = {
  lectureId: "42",
  title: "약자 4회차",
  description: null,
  status: "SCHEDULED",
  scheduledAt: null,
  startedAt: null,
  endedAt: null,
  currentProblem: null,
  createdAt: "2026-09-26T01:00:00.000Z",
  myParticipation: { joinedAt: "2026-09-26T01:00:00.000Z", leftAt: null },
  reviewableCurriculumStages: [],
};

describe("수업 계약", () => {
  it("학습자 스냅샷을 파싱한다", () => {
    const parsed = learnerLectureSchema.parse(learner);
    expect(parsed.lectureId).toBe("42");
    expect(parsed.status).toBe("SCHEDULED");
    expect(parsed.myParticipation.leftAt).toBeNull();
  });

  it("알 수 없는 상태는 거부한다", () => {
    expect(() => learnerLectureSchema.parse({ ...learner, status: "PAUSED" })).toThrow();
  });

  it("음성 토큰 응답을 파싱한다", () => {
    const parsed = mediaTokenSchema.parse({
      serverUrl: "wss://openvidu.i-eum.site",
      token: "jwt",
      expiresAt: "2026-09-26T01:10:00.000Z",
    });
    expect(parsed.serverUrl).toBe("wss://openvidu.i-eum.site");
  });

  it("wss가 아닌 서버 주소는 거부한다", () => {
    expect(() =>
      mediaTokenSchema.parse({ serverUrl: "http://x", token: "jwt", expiresAt: "2026-09-26T01:10:00.000Z" }),
    ).toThrow();
  });
});

const currentProblem = {
  problemId: "26578",
  problemType: "CONSONANT",
  targetText: "ㄱ",
  displayText: "초성 ㄱ",
  cellCount: 1,
  submitted: false,
};

const liveAnswer = {
  attemptId: "900",
  lectureId: "42",
  problemId: "26578",
  isCorrect: false,
  score: 0,
  attemptedAt: "2026-09-27T01:00:00.000Z",
  cells: [{ cellIndex: 1, inputPattern: 0, expectedPattern: 8, diffMask: 8, isCorrect: false }],
};

describe("수업 문제 계약", () => {
  it("현재 문제와 내 결과가 있는 학습자 스냅샷을 파싱한다", () => {
    const parsed = learnerLectureSchema.parse({ ...learner, status: "OPEN", currentProblem, myResult: liveAnswer });
    expect(parsed.currentProblem?.displayText).toBe("초성 ㄱ");
    expect(parsed.currentProblem?.submitted).toBe(false);
    expect(parsed.myResult?.cells[0]?.diffMask).toBe(8);
  });

  it("myResult가 없는 응답은 null로 채운다 (API 브랜치가 합쳐지기 전 서버와도 붙는다)", () => {
    const parsed = learnerLectureSchema.parse(learner);
    expect(parsed.currentProblem).toBeNull();
    expect(parsed.myResult).toBeNull();
  });

  it("학습자 현재 문제에 정답 점형이 섞여 와도 앱으로 넘기지 않는다", () => {
    const parsed = learnerLectureSchema.parse({ ...learner, currentProblem: { ...currentProblem, expectedPattern: [8] } });
    expect(parsed.currentProblem).not.toBeNull();
    expect("expectedPattern" in parsed.currentProblem!).toBe(false);
  });

  it("답안 칸 값이 6비트를 벗어나면 거부한다", () => {
    expect(() =>
      liveAnswerSchema.parse({ ...liveAnswer, cells: [{ ...liveAnswer.cells[0], inputPattern: 64 }] }),
    ).toThrow();
  });

  it("제출 요청은 1~12칸, 칸 값 0~63, durationMs 0 이상이다", () => {
    expect(liveAnswerRequestSchema.parse({ cells: [8], durationMs: 0 })).toEqual({ cells: [8], durationMs: 0 });
    expect(() => liveAnswerRequestSchema.parse({ cells: [], durationMs: 0 })).toThrow();
    expect(() => liveAnswerRequestSchema.parse({ cells: Array(13).fill(1), durationMs: 0 })).toThrow();
    expect(() => liveAnswerRequestSchema.parse({ cells: [64], durationMs: 0 })).toThrow();
    expect(() => liveAnswerRequestSchema.parse({ cells: [8], durationMs: -1 })).toThrow();
  });
});
