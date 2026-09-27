import { ApiError } from "@ieum/api-client";

/**
 * 세션 준비(`POST /learning-sessions`) 실패를 사용자에게 알리는 문구를 만든다.
 *
 * 화면 컴포넌트(`session/intro.tsx`)가 아니라 여기에 두는 이유는 `learningSessionCache.ts`와
 * 같다 — 화면 파일은 AsyncStorage·expo-router 의존 사슬 때문에 테스트에서 import할 수 없어,
 * 판정 로직을 화면 안에 두면 검증할 방법이 없다.
 *
 * **왜 시도 횟수를 문구에 담는가**: `speak()`(`a11y/speech.ts`)는 같은 문구가 1.5초 안에 다시
 * 들어오면 조용히 무시한다. 재시도가 같은 이유로 또 실패했을 때 문구가 고정이면 두 번째
 * 안내가 음성으로만 쓰는 사용자에게 씹힌다 — "다시 시도를 눌렀는데 아무 일도 안 일어난 것"처럼
 * 들린다. `analyzing.tsx`의 `buildRetryFailureMessage`가 같은 문제를 같은 방식으로 푼다.
 *
 * **왜 화면·낭독을 함께 돌려주는가**: 화면에 보이는 글과 낭독되는 말이 갈라지면 어느 쪽을
 * 믿어야 할지 알 수 없다. `speech`는 `headline` + `detail`을 그대로 이은 문장이다.
 *
 * **왜 권하는 행동까지 돌려주는가**: 문구와 버튼이 따로 결정되면 갈라진다. 실제로 갈라졌다 —
 * 문구는 "이 단계는 건너뛰고 다른 단계를 골라 주세요"로 바뀌었는데 주 버튼(위·primary)은
 * "다시 시도"로 남아 있었다. TalkBack 포커스도 거기 먼저 닿으므로, 안내가 하지 말라고 한
 * 행동이 가장 누르기 쉬운 자리에 있고 눌러도 영구 실패다 — 막으려던 무한 재시도가 그대로
 * 남는다. 둘을 한 곳에서 정해 다시 갈라지지 않게 한다.
 */
export type SessionStartRecovery = "retry" | "pick-other-stage";

export interface SessionStartFailure {
  /** 화면 헤더 (줄바꿈 포함) */
  headline: string;
  /** 화면 본문 */
  detail: string;
  /** 낭독 문구 — 화면에 보이는 글 전체와 같다 */
  speech: string;
  /**
   * 안내가 권하는 행동. 화면은 이것을 주 버튼(맨 위·`primary`)에 놓고 나머지를
   * `secondary`로 내린다 — 낭독이 권한 것과 화면이 권한 것이 같아야 한다.
   */
  recovery: SessionStartRecovery;
}

const ATTEMPT_ORDINAL_KO: Record<number, string> = {
  2: "두 번째",
  3: "세 번째",
  4: "네 번째",
  5: "다섯 번째",
};

function ordinal(errorCount: number): string {
  return ATTEMPT_ORDINAL_KO[errorCount] ?? `${errorCount}번째`;
}

/**
 * 그 단계에 낼 문제가 아직 없다는 서버 응답(`LEARNING_CONTENT_NOT_READY`, 503).
 *
 * **재시도로는 절대 풀리지 않는다.** 출제 후보가 12문항에 못 미치면 백엔드가 이 오류를
 * 던지는데, 시드가 채워지기 전까지 약어(5개)·문장(0개) 단계는 영구 실패다. 원인을 가린 채
 * "잠시 후 다시 해 보세요"라고만 하면 학습자는 무한히 재시도한다 — 다른 단계를 고르라고
 * 말해야 하고, 화면(`session/intro.tsx`)에는 "다른 단계 복습" 버튼이 이미 있다.
 */
const CONTENT_NOT_READY_CODE = "LEARNING_CONTENT_NOT_READY";

/**
 * @param errorCount 이 쿼리가 지금까지 실패한 횟수 (`useQuery`의 `errorUpdateCount`).
 *   0이나 1이면 첫 실패다.
 * @param error 실패의 원인(`useQuery`의 `error`). **화면이 이것을 넘기지 않으면** 원인을
 *   가리지 않는 일반 안내만 나가고, "이 단계에는 문제가 없다"는 사실이 사용자에게 영영
 *   닿지 않는다.
 */
export function buildSessionStartFailure(
  errorCount: number,
  error?: unknown,
): SessionStartFailure {
  if (error instanceof ApiError && error.code === CONTENT_NOT_READY_CODE) {
    // `headline`과 `detail`은 겹치지 않는 말을 한다 — 둘을 이어 붙인 것이 그대로 낭독
    // 문구가 되므로, 같은 말을 양쪽에 넣으면 그 반복이 음성 사용자에게 고스란히 간다
    const headline = "아직 문제가\n준비되지 않았어요";
    // 재시도해도 결과는 같지만 문구는 매번 달라져야 한다 — `speak()`는 같은 문구를 1.5초
    // 안에 다시 받으면 조용히 무시하므로, 고정하면 두 번째 안내가 음성 사용자에게 씹힌다
    const detail =
      errorCount <= 1
        ? "이 단계는 건너뛰고 다른 단계를 골라 주세요."
        : `${ordinal(errorCount)} 시도에도 준비되지 않았어요. 이 단계는 건너뛰고 다른 단계를 골라 주세요.`;
    return {
      headline,
      detail,
      speech: `아직 문제가 준비되지 않았어요. ${detail}`,
      // 재시도로는 절대 풀리지 않으므로 "다시 시도"를 주 버튼에 두면 안 된다
      recovery: "pick-other-stage",
    };
  }

  const headline = "학습 준비가\n안 됐어요";
  const detail =
    errorCount <= 1
      ? "잠시 후 다시 해 보세요."
      : `${ordinal(errorCount)} 시도에도 안 됐어요. 잠시 후 다시 해 보세요.`;
  // 원인을 모르는 실패다. "잠시 후 다시 해 보세요"라고 말했으니 재시도가 주 버튼이다
  return { headline, detail, speech: `학습 준비가 안 됐어요. ${detail}`, recovery: "retry" };
}
