import type { InProgressSession } from "@ieum/contracts";

/**
 * 홈이 진행 중 세션 하나를 버튼으로 보여줄 때의 문구.
 *
 * 모두 풀고 완료 제출만 남은 세션은 풀 것이 없다. "이어서"라고 부르면 눌렀을 때 문항 화면이
 * 아니라 결과 화면으로 가서 이름이 거짓이 된다.
 */
export function describeResumable(session: InProgressSession): { label: string; progress: string } {
  const finished = session.completedCount >= session.totalCount;
  const label = session.review
    ? finished
      ? "복습 결과 받기"
      : "이어서 복습"
    : finished
      ? "결과 받기"
      : "이어서 학습";
  return {
    label,
    progress: `${session.totalCount}문항 중 ${session.completedCount}문항 완료`,
  };
}

/**
 * 음성 중심 홈에 도착했을 때 읽을 문장. 학습과 복습을 따로 알린다 — 홈이 두 종류를 각자의
 * 버튼으로 보여주므로, 하나만 말하면 다른 쪽이 있다는 것을 귀로는 알 수 없다.
 */
export function homeArrivalSpeech(params: {
  userName: string;
  stepLabel: string;
  learning: InProgressSession | null;
  review: InProgressSession | null;
}): string {
  const { userName, stepLabel, learning, review } = params;
  if (!learning && !review) {
    return "홈이에요. 진행 중인 학습이 없어요. 복습을 시작하거나 새 단계를 배울 수 있어요.";
  }
  const parts = [`홈이에요. ${userName}님, ${stepLabel}를 배우는 중이에요.`];
  if (learning) parts.push(`${learning.stageName} 학습이 진행 중이에요.`);
  if (review) parts.push(learning ? "진행 중인 복습도 있어요." : "진행 중인 복습이 있어요.");
  return parts.join(" ");
}
