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

export function selectSessionSummaryFeedback(
  serverResult: Pick<CompletionResponse, "summary" | "ttsText"> | null,
): SessionSummaryFeedback {
  const ttsText = serverResult?.ttsText ?? null;
  const summary = serverResult?.summary ?? null;

  // ttsText(낭독용 문장)를 먼저, summary(화면용 설명)를 이어서 보여준다. 서버가 같은 문자열을
  // 두 필드에 줄 수도 있으므로 중복이면 한 번만 그린다
  const candidates = [ttsText, summary];
  const lines: string[] = [];
  for (const text of candidates) {
    if (text !== null && text.length > 0 && !lines.includes(text)) {
      lines.push(text);
    }
  }

  return { lines, speech: lines.length > 0 ? lines.join(" ") : null };
}
