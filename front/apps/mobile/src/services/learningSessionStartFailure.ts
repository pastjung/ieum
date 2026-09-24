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
 */
export interface SessionStartFailure {
  /** 화면 헤더 (줄바꿈 포함) */
  headline: string;
  /** 화면 본문 */
  detail: string;
  /** 낭독 문구 — 화면에 보이는 글 전체와 같다 */
  speech: string;
}

const ATTEMPT_ORDINAL_KO: Record<number, string> = {
  2: "두 번째",
  3: "세 번째",
  4: "네 번째",
  5: "다섯 번째",
};

/**
 * @param errorCount 이 쿼리가 지금까지 실패한 횟수 (`useQuery`의 `errorUpdateCount`).
 *   0이나 1이면 첫 실패다.
 */
export function buildSessionStartFailure(errorCount: number): SessionStartFailure {
  const headline = "학습 준비가\n안 됐어요";
  const detail =
    errorCount <= 1
      ? "잠시 후 다시 해 보세요."
      : `${ATTEMPT_ORDINAL_KO[errorCount] ?? `${errorCount}번째`} 시도에도 안 됐어요. 잠시 후 다시 해 보세요.`;
  return { headline, detail, speech: `학습 준비가 안 됐어요. ${detail}` };
}
