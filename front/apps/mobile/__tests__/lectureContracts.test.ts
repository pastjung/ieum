import { learnerLectureSchema, mediaTokenSchema } from "@ieum/contracts";

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
