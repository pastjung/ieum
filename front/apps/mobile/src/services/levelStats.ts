import type { Level, LevelStats } from "@ieum/contracts";
import { LEVEL_LABEL, LEVEL_ORDER } from "@/services/levelLabels";

/** 음성 중심 화면이 한 번에 읽어 줄 단계 수. 목록이 길수록 귀로 남는 것이 없다 */
const VOICE_LEVEL_LIMIT = 3;

export interface LevelRow {
  level: Level;
  /** 단계의 한글 이름 (`LEVEL_LABEL`) */
  label: string;
  solvedCount: number;
  correctCount: number;
  /**
   * 0~100 정답률. **아직 한 번도 풀지 않았으면 null이다.**
   *
   * 0과 null은 다른 사실이다 — 0은 "풀었는데 다 틀렸다", null은 "아직 안 풀었다"다.
   * 안 푼 단계를 0%로 그리면 학습자는 자기가 다 틀린 것으로 읽는다.
   */
  percent: number | null;
}

export interface LevelDashboard {
  /** 여섯 단계가 학습 순서로 항상 들어 있다 */
  rows: LevelRow[];
  totalSolved: number;
  totalCorrect: number;
  /** 전체 정답률. 기록이 없으면 null */
  overallPercent: number | null;
}

function percentOf(solvedCount: number, correctCount: number): number | null {
  // 0으로 나누면 NaN이 화면에 그대로 나온다
  if (solvedCount === 0) return null;
  return Math.round((correctCount / solvedCount) * 100);
}

/**
 * "내 실력" 화면이 그릴 값.
 *
 * <p>예전에는 `user_skill_memory`의 요소별 숙련도를 보여줬다. 요소가 74개라
 * 목록이 읽히지 않았고, 숙련도는 AI가 계산한 확률이라 "내가 몇 개를 맞혔나"와는 다른 값이어서
 * 학습자가 자기 정답률로 읽으면 틀린 말이 됐다. 이제 실제 풀이 기록에서 단계별 정답률을 낸다.
 *
 * <p>**여섯 단계를 여기서 채운다.** 서버는 기록이 있는 단계만 보낸다 — Level은 값이 고정된
 * enum이라 앱이 여섯 개 전부와 그 표시 순서·이름을 이미 들고 있고(`levelLabels.ts`), 서버가
 * 빈 줄을 채워 보내도 이 함수가 다시 채우는 일이 겹칠 뿐이다. 순서도 서버를 믿지 않고
 * `LEVEL_ORDER`(자음·모음·약자·단어·약어·문장)를 따른다.
 *
 * <p>화면 파일이 아니라 여기에 두는 이유는 `themeStats.ts`와 같다 — 화면 파일은
 * AsyncStorage·expo-router 의존 사슬 때문에 테스트에서 import할 수 없다.
 */
export function selectLevelDashboard(
  stats: LevelStats | null | undefined,
): LevelDashboard {
  const byLevel = new Map(stats?.levels.map((stat) => [stat.level, stat]) ?? []);

  const rows = LEVEL_ORDER.map((level): LevelRow => {
    const stat = byLevel.get(level);
    const solvedCount = stat?.solvedCount ?? 0;
    const correctCount = stat?.correctCount ?? 0;
    return {
      level,
      label: LEVEL_LABEL[level],
      solvedCount,
      correctCount,
      percent: percentOf(solvedCount, correctCount),
    };
  });

  const totalSolved = rows.reduce((sum, row) => sum + row.solvedCount, 0);
  const totalCorrect = rows.reduce((sum, row) => sum + row.correctCount, 0);

  return {
    rows,
    totalSolved,
    totalCorrect,
    overallPercent: percentOf(totalSolved, totalCorrect),
  };
}

/**
 * 음성 모드가 읽어 줄 단계 — 정답률이 낮은 것부터 세 개.
 *
 * <p>**아직 안 푼 단계는 넣지 않는다.** 기록이 없는 단계를 "0퍼센트"로 읽으면 다 틀렸다는
 * 말이 되고, 그 단계가 늘 맨 앞에 와서 정작 약한 단계를 가린다.
 */
export function weakestLevels(rows: readonly LevelRow[]): LevelRow[] {
  return rows
    .filter((row) => row.percent !== null)
    .sort((a, b) => (a.percent ?? 0) - (b.percent ?? 0))
    .slice(0, VOICE_LEVEL_LIMIT);
}
