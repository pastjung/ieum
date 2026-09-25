import type { CompletionResponse } from "@ieum/contracts";

/**
 * 결과 화면(`session/summary.tsx`)에 보여줄 세션 요약.
 *
 * 서버가 요약을 만들지 못하면(`SessionSummaryResult.notAvailable()`) `summary`·`ttsText`가
 * 둘 다 null로 온다 — 그때는 보여줄 것이 없다(`lines`가 빈 배열). 목 세션(`serverResult`가
 * null)도 마찬가지다. 두 경우 모두 지금 화면 그대로 두는 것이 맞다 — 없는 요약을 꾸며 낼
 * 수는 없다.
 *
 * `lines`(화면에 그릴 문장들)와 `speech`(낭독 문구)를 항상 같은 재료로 만든다 — 화면에만
 * 있고 낭독되지 않는 문장, 혹은 낭독만 되고 화면에 없는 문장이 생기지 않게 하기 위해서다.
 * 이 브랜치에서 그 규칙을 어긴 결함이 네 번 나왔다.
 */
export interface SessionSummaryFeedback {
  /** 화면에 순서대로 그릴 문장. 비어 있으면 요약 블록 자체를 그리지 않는다 */
  lines: string[];
  /** lines를 이어 붙인 낭독 문구. lines가 비어 있으면 null — 아무것도 낭독하지 않는다 */
  speech: string | null;
}

/**
 * 서버 요약 문구가 앞에 붙이는 점수 문장.
 *
 * `apps/ai/app/application/session_services.py`의 `summarize_session`이 두 필드를 이렇게
 * 만든다.
 *
 * ```python
 * summary = f"{n}문제 중 {n_correct}문제를 맞혔어요."
 * tts     = f"오늘 {n}문제 중 {n_correct}문제를 맞혔어요."
 * ```
 *
 * **이 문장을 쓰지 않는다.** 두 가지 이유가 있다.
 *
 * 1. 결과 화면은 자기 점수 문장("오늘의 결과예요. N문제 중 M문제를 맞혔어요.")을 이미
 *    낭독한다. 서버 문구를 그대로 이어 붙이면 한 화면에서 점수를 세 번 말하게 된다.
 * 2. **두 점수가 다른 채점에서 나온다.** 서버 요약의 `n_correct`는 AI가 `target_text`를
 *    규칙 변환해 다시 채점한 값이고(`_grade` — `expected_patterns`는 경고만 남기고 채점에
 *    쓰지 않는다), 화면 숫자는 백엔드가 DB 시드 셀로 채점한 `correctProblemCount`다. 갈리면
 *    사용자는 "8 / 12"를 보면서 "9문제를 맞혔어요"를 듣는다.
 *
 * 점수는 앱이 책임진다 — 한 화면에서 점수가 한 번만, 한 숫자로 나와야 한다. 서버에서는
 * 점수 뒤에 붙는 조언("…을 다시 연습해 보세요", "아주 잘했어요")만 가져온다.
 */
const SCORE_SENTENCE = /^\s*(?:오늘\s*)?\d+문제 중 \d+문제를 맞혔어요\.\s*/;

function adviceOnly(text: string | null | undefined): string | null {
  if (text === null || text === undefined) return null;
  const rest = text.replace(SCORE_SENTENCE, "").trim();
  return rest.length > 0 ? rest : null;
}

export function selectSessionSummaryFeedback(
  serverResult: Pick<CompletionResponse, "summary" | "ttsText"> | null,
): SessionSummaryFeedback {
  // `summary`와 `ttsText`는 어미만 다른 같은 문장이다(위 주석의 파이썬 코드 참고) — 둘 다
  // 보여줄 이유가 없다. 낭독을 전제로 다듬어진 `ttsText`를 먼저 쓰고, 그쪽이 점수 문장뿐이면
  // `summary`로 물러선다.
  const advice = adviceOnly(serverResult?.ttsText) ?? adviceOnly(serverResult?.summary);
  const lines = advice === null ? [] : [advice];

  return { lines, speech: lines.length > 0 ? lines.join(" ") : null };
}
