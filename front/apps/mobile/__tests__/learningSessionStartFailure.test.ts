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
});
