import type { InProgressSession } from "@ieum/contracts";
import type { SessionRequest } from "./learningSessionCache";

export interface SessionConflict {
  headline: string;
  detail: string;
  /** 화면 문구를 이어 붙인 낭독 문구 */
  speech: string;
}

/**
 * 새 세션을 만들면 진행 중인 학습이 닫히는지 판단하고, 그때 보여줄 안내를 만든다.
 *
 * <p>`ProblemService.createSession`은 새 세션을 만들 때 **같은 종류의** 미완료 세션을 모두
 * 완료 처리한다. 행이 지워지지는 않지만 학습자는 이어서 풀 기회를 잃고, `/completion`을
 * 거치지 않은 채 "완료"로 기록되어 요약도 오답 기록 갱신도 없다.
 *
 * 종류가 다르면 서로를 닫지 않는다 — 단계 학습 중에 복습을 시작해도 단계 학습은 남는다.
 * 그때까지 물어보면 잃는 것이 없는데도 학습자를 멈춰 세우게 된다.
 *
 * `undefined`는 아직 조회 중이라는 뜻이라 확인하지 않는다. 그 사이에 물으면 진행 중인 것이
 * 없는데도 경고가 뜬다.
 */
export function describeSessionConflict(
  request: SessionRequest,
  inProgress: InProgressSession | null | undefined,
): SessionConflict | null {
  // 이어받기는 새 세션을 만들지 않으므로 아무것도 닫지 않는다
  if (request.kind === "resume" || !inProgress) {
    return null;
  }
  const startsReview = request.kind === "review";
  if (startsReview !== inProgress.review) {
    return null;
  }

  const headline = `진행 중인 ${inProgress.stageName} 학습이 있어요.`;
  const detail =
    `${inProgress.totalCount}문항 중 ${inProgress.completedCount}문항까지 풀었어요. ` +
    "새로 시작하면 이 학습은 여기서 끝나고 이어서 풀 수 없어요.";
  return { headline, detail, speech: `${headline} ${detail}` };
}
