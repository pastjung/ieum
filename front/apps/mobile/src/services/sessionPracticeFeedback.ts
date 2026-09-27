import type { CompletionResponse } from "@ieum/contracts";

/**
 * 오류 유형 키의 한글 이름.
 *
 * 키는 백엔드 `ErrorType` enum(`com.itda.problem.domain.ErrorType`)을 소문자로 옮긴 값이고,
 * AI가 그대로 돌려준다. 여기 문구는 **짧은 이름**이다 — AI의 `EXPLAIN`은 "점 누락이
 * 반복됐어요" 같은 완성된 문장이라 `weakSkills.explanation` 안에 이미 들어 있고, 이 표는
 * 횟수와 나란히 세는 자리("점 빠짐 3번")에 쓴다.
 */
const ERROR_TYPE_LABEL: Record<string, string> = {
  dot_missing: "점 빠짐",
  dot_extra: "점 더 찍음",
  dot_mixed: "점 위치 다름",
  mirror: "좌우 뒤집힘",
  vflip: "위아래 뒤집힘",
  rot180: "반 바퀴 돌아감",
  shift_down: "한 줄 아래로 밀림",
  shift_up: "한 줄 위로 밀림",
  jamo_confusion: "다른 글자와 혼동",
  cell_omitted: "칸 빠뜨림",
  cell_inserted: "칸 더 넣음",
  transposition: "칸 순서 바뀜",
  abbr_expanded: "약자를 풀어 씀",
  abbr_overused: "약자를 쓰지 않을 자리에 씀",
  marker_missing: "된소리표·구분표 빠뜨림",
  silent_onset_written: "첫소리 이응을 적음",
};

export interface PracticeRow {
  /** 화면에 그리는 문장 */
  text: string;
  /** 오른쪽 수준 칩. 오답 유형 줄에는 없다 */
  level: string | null;
  /** "더 연습해요" 인 줄만 강조한다 */
  needsPractice: boolean;
}

export interface PracticeFeedback {
  title: string;
  /** 비어 있으면 블록 자체를 그리지 않는다 */
  rows: PracticeRow[];
}

const EMPTY: PracticeFeedback = { title: "", rows: [] };

/**
 * 결과 화면의 "더 연습할 것" 블록을 만든다.
 *
 * 예전에는 이 자리에 `mockData.skillLevels`의 **고정 3줄**이 들어갔다. 바로 위 점수는
 * 서버가 채점한 실제 값이라, 학습자는 방금 푼 것과 무관한 고정값을 자기 실력으로 읽었다.
 *
 * 재료는 둘 다 완료 응답에 이미 들어 있다.
 *
 * 1. `weakSkills` — AI가 고른 약한 요소. 숙련도가 낮은 것부터 정렬돼 오고 "익숙해요"는
 *    빠져 있다. 다만 그 요소에 기록이 2건 이상이어야 판정하므로 처음 몇 세션은 비어 있다.
 * 2. `errorTypeCounts` — 이번 세션의 오류 유형별 횟수. 틀린 것이 있으면 언제나 채워진다.
 *
 * 그래서 1을 먼저 쓰고, 없으면 2로 물러선다. 둘 다 비어 있는 경우는 **틀린 것이 없었다는
 * 뜻**이다(`error_type_counts`는 세션 전체를 센다) — 그때만 "없었어요"라고 말한다.
 *
 * 서버 결과 자체가 없으면(목 세션이거나 완료 제출 실패) 아무것도 그리지 않는다. 없는
 * 실력 판정을 꾸며 낼 수는 없다.
 */
export function selectPracticeFeedback(
  serverResult: Pick<CompletionResponse, "weakSkills" | "errorTypeCounts"> | null,
): PracticeFeedback {
  if (serverResult === null) return EMPTY;

  const weakSkills = serverResult.weakSkills ?? [];
  if (weakSkills.length > 0) {
    return {
      title: "더 연습할 것",
      // 서버가 보낸 순서를 지킨다 — AI가 숙련도가 낮은 것부터 정렬해 보낸다
      rows: weakSkills.map((skill) => ({
        text: skill.explanation,
        level: skill.label,
        needsPractice: skill.label === "더 연습해요",
      })),
    };
  }

  const errorCounts = Object.entries(serverResult.errorTypeCounts ?? {}).filter(
    ([, count]) => count > 0,
  );
  if (errorCounts.length > 0) {
    return {
      title: "이번에 자주 나온 실수",
      rows: errorCounts
        .sort(([, a], [, b]) => b - a)
        .map(([type, count]) => ({
          // 모르는 키가 와도 줄을 버리지 않는다. 백엔드 ErrorType에 값이 늘면 앱이 모르는
          // 키가 오는데, 버리면 학습자는 자기 오답이 없었던 것처럼 보게 된다
          text: `${ERROR_TYPE_LABEL[type] ?? type} ${count}번`,
          level: null,
          needsPractice: false,
        })),
    };
  }

  return {
    title: "이번 학습",
    rows: [{ text: "눈에 띄는 실수가 없었어요.", level: null, needsPractice: false }],
  };
}
