/**
 * 제출하려는 답이 그 문항이 받을 수 있는 칸 수를 넘었는지 본다. 넘었으면 사용자에게 읽어 줄
 * 문구를, 괜찮으면 `null`을 돌려준다.
 *
 * **왜 넘치는 입력만 막는가**: 백엔드는 칸 수가 정확히 맞아야 답을 받는다
 * (`LearningSessionCompletionService.validateCellInputs` — 하나라도 어긋나면 세션 전체가
 * 롤백된다). 그래서 `sessionStore.buildAttempts`가 길이를 맞춰 보내는데, 넘친 답을 잘라 보내면
 * 채점이 갈라진다: `gradeAnswer`는 앞 칸이 다 맞아도 칸이 남으면 오답으로 판정하지만
 * (`actual.length > expected.length` 분기), 잘라 보낸 답을 서버는 정답으로 센다. 그러면 사용자는
 * 문항마다 "틀렸어요"를 듣고 결과 화면에서는 만점을 본다.
 *
 * 모자란 답은 막지 않는다 — 초보 학습자의 가장 흔한 오답이고, 빈 칸으로 채워 보내도
 * `gradeAnswer`(`?? EMPTY_CELL`)와 서버가 똑같이 오답으로 판정해 어긋나지 않는다.
 *
 * 화면(`play.tsx`)이 아니라 여기에 두는 이유는 `learningSessionCache.ts`와 같다 — 화면 파일은
 * AsyncStorage·expo-router 의존 사슬 때문에 테스트에서 import할 수 없다.
 *
 * 문구는 "무엇을 해야 하는지"를 담는다: 몇 칸까지인지, 지금 몇 칸인지, 어떻게 지우는지.
 * 이 앱은 낭독이 주 출력이라 "안 됩니다"만 말하면 사용자는 다음 행동을 알 수 없다.
 */
export function describeTooManyCells(cellCount: number, expectedLength: number): string | null {
  if (cellCount <= expectedLength) return null;
  return `이 문제는 ${expectedLength}칸까지예요. 지금 ${cellCount}칸 입력했어요. 지우기 버튼을 누르면 마지막 칸이 지워져요.`;
}
