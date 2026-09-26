import { MAX_INPUT_CELLS } from "@ieum/contracts";

/**
 * 한 문항에 보낼 수 있는 칸 수를 넘었는지 본다. 넘었으면 사용자에게 읽어 줄 문구를,
 * 괜찮으면 `null`을 돌려준다.
 *
 * **기대 칸 수 초과는 더 이상 막지 않는다.** dev가 `validateExpectedCells`에서 길이 동등
 * 검사를 뺐다 — 칸 수가 다른 답은 이제 예외가 아니라 서버가 오답으로 채점한다(설계 4.1절).
 * 화면과 서버 채점이 갈릴 일이 없으니 여기서 막을 이유도 사라졌다.
 *
 * 대신 **AI 분석의 입력 상한(12칸, `MAX_INPUT_CELLS`)만 막는다.** FastAPI가
 * `input_patterns`를 12개까지만 받으므로(`apps/ai/.../backend/router.py`), 그 이상을 보내면
 * 기대 칸 수와 무관하게 거부된다 — 5칸 문제에 13칸을 넣어도 막힌다.
 *
 * 화면(`play.tsx`)이 아니라 여기에 두는 이유는 `learningSessionCache.ts`와 같다 — 화면 파일은
 * AsyncStorage·expo-router 의존 사슬 때문에 테스트에서 import할 수 없다.
 *
 * 문구는 "무엇을 해야 하는지"를 담는다: 몇 칸까지인지, 지금 몇 칸인지, 어떻게 지우는지.
 * 이 앱은 낭독이 주 출력이라 "안 됩니다"만 말하면 사용자는 다음 행동을 알 수 없다.
 */
export function describeTooManyCells(cellCount: number): string | null {
  if (cellCount <= MAX_INPUT_CELLS) return null;
  return `한 번에 보낼 수 있는 답은 ${MAX_INPUT_CELLS}칸까지예요. 지금 ${cellCount}칸 입력했어요. 지우기 버튼을 누르면 마지막 칸이 지워져요.`;
}
