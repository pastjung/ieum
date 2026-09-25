import { selectPracticeFeedback } from "@/services/sessionPracticeFeedback";
import type { CompletionResponse, WeakSkill } from "@ieum/contracts";

function weak(overrides: Partial<WeakSkill>): WeakSkill {
  return {
    learningSkillId: 23,
    masteryProb: 0.31,
    label: "더 연습해요",
    dominantErrorType: "dot_missing",
    explanation: "받침 이응에서 점 누락이 반복됐어요.",
    ...overrides,
  };
}

function completion(overrides: Partial<CompletionResponse>): CompletionResponse {
  return {
    sessionId: 1,
    completedAt: "2026-09-25T00:00:00Z",
    totalProblemCount: 12,
    correctProblemCount: 8,
    problemResults: [],
    summary: null,
    ttsText: null,
    errorTypeCounts: {},
    weakSkills: [],
    ...overrides,
  } as CompletionResponse;
}

describe("결과 화면의 '더 연습할 것' 블록", () => {
  it("약한 학습요소가 있으면 그 설명과 수준을 줄로 만든다", () => {
    const result = selectPracticeFeedback(
      completion({
        weakSkills: [
          weak({ explanation: "받침 이응에서 점 누락이 반복됐어요.", label: "더 연습해요" }),
          weak({ learningSkillId: 66, explanation: "모음 ㅟ에서 점 위치가 자주 달랐어요.", label: "배우는 중" }),
        ],
      }),
    );

    expect(result.rows).toHaveLength(2);
    expect(result.rows[0]).toEqual({
      text: "받침 이응에서 점 누락이 반복됐어요.",
      level: "더 연습해요",
      needsPractice: true,
    });
    expect(result.rows[1].level).toBe("배우는 중");
    expect(result.rows[1].needsPractice).toBe(false);
  });

  it("서버가 준 순서를 지킨다 — AI가 숙련도가 낮은 것부터 정렬해 보낸다", () => {
    const result = selectPracticeFeedback(
      completion({
        weakSkills: [
          weak({ learningSkillId: 1, explanation: "첫째." }),
          weak({ learningSkillId: 2, explanation: "둘째." }),
          weak({ learningSkillId: 3, explanation: "셋째." }),
        ],
      }),
    );

    expect(result.rows.map((r) => r.text)).toEqual(["첫째.", "둘째.", "셋째."]);
  });

  it("약한 요소가 없으면 이번 세션의 오답 유형을 대신 보여준다", () => {
    // weakSkills는 그 요소에 기록이 2건 이상 있어야 채워진다(`n_attempts < 2`면 건너뛴다).
    // 처음 몇 세션은 비어 있는데, 그때도 errorTypeCounts에는 실제 오답이 들어 있다
    const result = selectPracticeFeedback(
      completion({
        weakSkills: [],
        errorTypeCounts: { dot_missing: 3, jamo_confusion: 2, shift_down: 1 },
      }),
    );

    expect(result.rows.map((r) => r.text)).toEqual([
      "점 빠짐 3번",
      "다른 글자와 혼동 2번",
      "한 줄 아래로 밀림 1번",
    ]);
    expect(result.rows.every((r) => r.level === null)).toBe(true);
  });

  it("오답 유형은 많은 것부터 보여준다", () => {
    const result = selectPracticeFeedback(
      completion({ errorTypeCounts: { shift_up: 1, dot_extra: 5, mirror: 3 } }),
    );

    expect(result.rows.map((r) => r.text)).toEqual([
      "점 더 찍음 5번",
      "좌우 뒤집힘 3번",
      "한 줄 위로 밀림 1번",
    ]);
  });

  it("모르는 오류 유형 키가 와도 그 줄을 버리지 않는다", () => {
    // 백엔드 ErrorType에 값이 늘면 앱이 모르는 키가 온다. 줄을 버리면 학습자는 자기
    // 오답이 없었던 것처럼 보게 된다 — 키를 그대로라도 보여주는 편이 낫다
    const result = selectPracticeFeedback(
      completion({ errorTypeCounts: { brand_new_kind: 2 } }),
    );

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0].text).toContain("2번");
  });

  it("약한 요소도 오답도 없으면 그렇게 말한다", () => {
    const result = selectPracticeFeedback(
      completion({ weakSkills: [], errorTypeCounts: {}, correctProblemCount: 12 }),
    );

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0].level).toBeNull();
    expect(result.rows[0].text).toContain("없었어요");
  });

  it("서버 결과가 없으면 블록을 그리지 않는다", () => {
    // 목 세션이거나 완료 제출이 실패한 경우 — 없는 실력 판정을 꾸며 내지 않는다
    expect(selectPracticeFeedback(null).rows).toEqual([]);
  });

  it("줄마다 제목이 내용과 맞는다", () => {
    const withWeak = selectPracticeFeedback(completion({ weakSkills: [weak({})] }));
    const withErrors = selectPracticeFeedback(
      completion({ errorTypeCounts: { dot_missing: 1 } }),
    );

    expect(withWeak.title).toContain("더 연습할 것");
    expect(withErrors.title).not.toBe(withWeak.title);
  });
});
