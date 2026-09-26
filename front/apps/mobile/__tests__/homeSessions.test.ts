import { describeResumable, homeArrivalSpeech } from "../src/services/homeSessions";
import type { InProgressSession } from "@ieum/contracts";

function session(overrides: Partial<InProgressSession>): InProgressSession {
  return {
    sessionId: 7,
    stageName: "자음",
    review: false,
    totalCount: 12,
    completedCount: 5,
    ...overrides,
  };
}

describe("홈 버튼의 진행 중 세션 문구", () => {
  it("풀 문항이 남은 학습은 이어서 학습이다", () => {
    expect(describeResumable(session({}))).toEqual({
      label: "이어서 학습",
      progress: "12문항 중 5문항 완료",
    });
  });

  it("풀 문항이 남은 복습은 이어서 복습이다", () => {
    expect(describeResumable(session({ review: true, stageName: "복습" })).label).toBe(
      "이어서 복습",
    );
  });

  it("다 푼 세션은 결과만 받으면 되므로 이어서라고 부르지 않는다", () => {
    // 눌렀을 때 문항 화면이 아니라 결과 화면으로 간다
    expect(describeResumable(session({ completedCount: 12 })).label).toBe("결과 받기");
    expect(describeResumable(session({ review: true, completedCount: 12 })).label).toBe(
      "복습 결과 받기",
    );
  });
});

describe("음성 중심 홈 도착 안내", () => {
  const base = { userName: "민지", stepLabel: "자음" };

  it("진행 중인 것이 없으면 새로 시작할 수 있다고 말한다", () => {
    expect(homeArrivalSpeech({ ...base, learning: null, review: null })).toBe(
      "홈이에요. 진행 중인 학습이 없어요. 복습을 시작하거나 새 단계를 배울 수 있어요.",
    );
  });

  it("학습과 복습이 함께 진행 중이면 둘 다 말한다", () => {
    // 홈이 두 종류를 각자의 버튼으로 보여주므로 하나만 말하면 다른 쪽이 있는지 귀로는 모른다
    const speech = homeArrivalSpeech({
      ...base,
      learning: session({ stageName: "모음" }),
      review: session({ review: true, stageName: "복습" }),
    });

    expect(speech).toContain("모음 학습이 진행 중이에요.");
    expect(speech).toContain("진행 중인 복습도 있어요.");
  });

  it("복습만 진행 중이면 학습이 있는 것처럼 말하지 않는다", () => {
    const speech = homeArrivalSpeech({
      ...base,
      learning: null,
      review: session({ review: true, stageName: "복습" }),
    });

    expect(speech).toContain("진행 중인 복습이 있어요.");
    expect(speech).not.toContain("학습이 진행 중");
    expect(speech).not.toContain("복습도");
  });
});
