import { ApiError } from "@ieum/api-client";
import { buildSessionStartFailure } from "@/services/learningSessionStartFailure";

describe("세션 준비 실패 안내", () => {
  it("첫 실패는 무엇을 하면 되는지 알려 준다", () => {
    const failure = buildSessionStartFailure(1);

    expect(failure.detail).toBe("잠시 후 다시 해 보세요.");
    expect(failure.speech).toBe("학습 준비가 안 됐어요. 잠시 후 다시 해 보세요.");
  });

  it("아직 한 번도 실패하지 않은 값(0)도 첫 실패로 본다", () => {
    expect(buildSessionStartFailure(0)).toEqual(buildSessionStartFailure(1));
  });

  it("재시도가 또 실패하면 다른 문구가 된다", () => {
    // speak()는 같은 문구가 1.5초 안에 다시 들어오면 조용히 무시한다. 문구가 고정이면
    // 두 번째 실패 안내가 음성으로만 쓰는 사용자에게 씹힌다
    const first = buildSessionStartFailure(1);
    const second = buildSessionStartFailure(2);
    const third = buildSessionStartFailure(3);

    expect(second.speech).not.toBe(first.speech);
    expect(third.speech).not.toBe(second.speech);
    expect(second.detail).toBe("두 번째 시도에도 안 됐어요. 잠시 후 다시 해 보세요.");
    expect(third.detail).toBe("세 번째 시도에도 안 됐어요. 잠시 후 다시 해 보세요.");
  });

  it("표에 없는 횟수도 숫자로 읽어 준다", () => {
    expect(buildSessionStartFailure(7).detail).toBe(
      "7번째 시도에도 안 됐어요. 잠시 후 다시 해 보세요.",
    );
  });

  it("낭독 문구가 화면에 보이는 글 전체와 같다", () => {
    // 화면과 낭독이 갈라지면 사용자는 어느 쪽을 믿어야 할지 알 수 없다
    for (const count of [1, 2, 5, 9]) {
      const failure = buildSessionStartFailure(count);
      const onScreen = `${failure.headline.replace("\n", " ")}. ${failure.detail}`;

      expect(failure.speech).toBe(onScreen);
    }
  });

  it("원인을 모르는 실패는 재시도를 권한다", () => {
    // "잠시 후 다시 해 보세요"라고 말했으니 주 버튼도 재시도여야 한다
    expect(buildSessionStartFailure(1).recovery).toBe("retry");
    expect(buildSessionStartFailure(1).speech).toContain("다시 해 보세요");
  });
});

/**
 * 백엔드는 출제 후보가 12문항에 못 미치면 `LEARNING_CONTENT_NOT_READY`(503)를 던진다.
 * 시드가 채워지기 전까지 약어(5개)·문장(0개) 단계는 **영구 실패**다 — 원인을 가린 채
 * "잠시 후 다시 해 보세요"라고만 하면 학습자는 무한히 재시도한다.
 */
describe("문제가 준비되지 않은 단계", () => {
  const notReady = new ApiError(
    503,
    "LEARNING_CONTENT_NOT_READY",
    "학습 콘텐츠가 아직 준비되지 않았습니다.",
  );

  it("재시도가 아니라 다른 단계를 고르라고 안내한다", () => {
    const failure = buildSessionStartFailure(1, notReady);

    expect(failure.detail).toBe("이 단계는 건너뛰고 다른 단계를 골라 주세요.");
    expect(failure.speech).toBe(
      "아직 문제가 준비되지 않았어요. 이 단계는 건너뛰고 다른 단계를 골라 주세요.",
    );
    expect(failure.detail).not.toContain("잠시 후 다시 해 보세요");
  });

  it("화면 글과 낭독 문구가 같고, 같은 말을 두 번 하지 않는다", () => {
    for (const count of [1, 2, 7]) {
      const failure = buildSessionStartFailure(count, notReady);
      const onScreen = `${failure.headline.replace("\n", " ")}. ${failure.detail}`;

      expect(failure.speech).toBe(onScreen);
      // headline이 한 말을 detail이 또 하면 그 반복이 낭독에 그대로 간다
      expect(failure.detail).not.toContain("아직 문제가 준비되지 않았어요");
    }
  });

  it("재시도가 또 실패하면 다른 문구가 된다", () => {
    // 재시도해도 결과는 같지만, 문구가 고정이면 speak()의 1.5초 중복 제거에 걸려
    // 두 번째 안내가 음성으로만 쓰는 사용자에게 씹힌다
    const first = buildSessionStartFailure(1, notReady);
    const second = buildSessionStartFailure(2, notReady);

    expect(second.speech).not.toBe(first.speech);
    expect(second.detail).toBe(
      "두 번째 시도에도 준비되지 않았어요. 이 단계는 건너뛰고 다른 단계를 골라 주세요.",
    );
  });

  it("다른 오류는 기존 일반 안내 그대로다", () => {
    const other = new ApiError(500, "INTERNAL_ERROR", "서버 오류");

    expect(buildSessionStartFailure(1, other)).toEqual(buildSessionStartFailure(1));
  });

  it("권하는 행동이 '다른 단계 고르기'다 — 재시도가 아니다", () => {
    // 화면은 이것으로 주 버튼(맨 위·primary)을 정한다. 낭독이 "다른 단계를 골라 주세요"
    // 라고 해 놓고 "다시 시도"가 주 버튼이면, 안내가 하지 말라고 한 행동이 가장 누르기
    // 쉬운 자리(TalkBack 포커스도 먼저 닿는 자리)에 놓인다 — 눌러도 영구 실패다
    for (const count of [1, 2, 7]) {
      expect(buildSessionStartFailure(count, notReady).recovery).toBe("pick-other-stage");
    }
  });

  it("권하는 행동이 낭독 문구와 어긋나지 않는다", () => {
    // 문구와 버튼이 따로 결정되면 갈라진다 — 실제로 갈라졌던 결함이라 함께 고정한다
    const failure = buildSessionStartFailure(1, notReady);

    expect(failure.speech).toContain("다른 단계를 골라 주세요");
    expect(failure.recovery).toBe("pick-other-stage");
  });

  it("오류를 넘기지 않으면 일반 안내가 된다", () => {
    // 화면이 error를 빠뜨리면 원인이 사용자에게 닿지 않는다는 사실을 고정한다
    expect(buildSessionStartFailure(1).detail).toBe("잠시 후 다시 해 보세요.");
  });
});
