import { describeSessionConflict } from "../src/services/sessionStartConflict";
import type { InProgressSession } from "@ieum/contracts";

function inProgress(overrides: Partial<InProgressSession> = {}): InProgressSession {
  return {
    sessionId: 7,
    stageName: "단어",
    review: false,
    totalCount: 12,
    completedCount: 5,
    ...overrides,
  };
}

describe("새 세션이 진행 중인 학습을 닫는지", () => {
  it("같은 종류면 닫힌다 — 단계 학습 중에 단계 학습을 시작", () => {
    // ProblemService.createSession 이 같은 종류의 미완료 세션을 완료 처리한다
    const conflict = describeSessionConflict({ kind: "theme", themeId: "vowel" }, inProgress());

    expect(conflict).not.toBeNull();
    expect(conflict?.headline).toContain("단어");
  });

  it("같은 종류면 닫힌다 — 복습 중에 복습을 시작", () => {
    const conflict = describeSessionConflict(
      { kind: "review" },
      inProgress({ review: true, stageName: "복습" }),
    );

    expect(conflict).not.toBeNull();
    expect(conflict?.headline).toContain("복습");
  });

  it("종류가 다르면 닫히지 않는다 — 단계 학습 중에 복습을 시작", () => {
    // 커리큘럼 세션과 복습 세션은 서로를 닫지 않는다
    expect(describeSessionConflict({ kind: "review" }, inProgress())).toBeNull();
  });

  it("종류가 다르면 닫히지 않는다 — 복습 중에 단계 학습을 시작", () => {
    expect(
      describeSessionConflict({ kind: "theme", themeId: "vowel" }, inProgress({ review: true })),
    ).toBeNull();
  });

  it("이어받기는 새 세션을 만들지 않으므로 확인하지 않는다", () => {
    expect(describeSessionConflict({ kind: "resume", sessionId: 7 }, inProgress())).toBeNull();
  });

  it("진행 중인 학습이 없으면 확인하지 않는다", () => {
    expect(describeSessionConflict({ kind: "theme", themeId: "vowel" }, null)).toBeNull();
  });

  it("아직 조회 중이면(undefined) 확인하지 않는다", () => {
    // 조회가 끝나기 전에 경고를 띄우면 진행 중인 것이 없는데도 물어보게 된다
    expect(describeSessionConflict({ kind: "theme", themeId: "vowel" }, undefined)).toBeNull();
  });

  it("안내에 진도와 잃게 되는 것을 담는다", () => {
    const conflict = describeSessionConflict(
      { kind: "theme", themeId: "vowel" },
      inProgress({ stageName: "단어", totalCount: 12, completedCount: 5 }),
    );

    expect(conflict?.detail).toContain("12문항 중 5문항");
    expect(conflict?.detail).toContain("이어서 풀 수 없어요");
  });

  it("화면 문구와 낭독 문구가 같은 내용이다", () => {
    const conflict = describeSessionConflict({ kind: "theme", themeId: "vowel" }, inProgress());

    expect(conflict?.speech).toBe(`${conflict?.headline} ${conflict?.detail}`);
  });
});
