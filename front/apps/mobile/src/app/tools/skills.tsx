import { useEffect } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import { selectLevelDashboard, weakestLevels, type LevelRow } from "@/services/levelStats";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** 이 아래로는 "더 연습할 것"으로 강조한다. 테마 선택 화면의 게이지 색 기준과 같은 값이다 */
const NEEDS_PRACTICE_PERCENT = 60;

const LOADING_TEXT = "불러오는 중이에요…";
const LOAD_FAILED_TEXT = "내 실력을 불러오지 못했어요.";
const LOAD_FAILED_SPEECH = `내 실력이에요. ${LOAD_FAILED_TEXT} 다시 시도를 눌러 주세요.`;
const EMPTY_TEXT = "아직 기록이 없어요. 학습을 시작하면 알려드려요.";

/** 단계 한 줄을 낭독할 문장. 화면에 그리는 값과 같은 곳에서 만든다 */
function speechFor(row: LevelRow): string {
  return `${row.label}, ${row.percent}퍼센트`;
}

/** 아직 안 푼 단계의 표시. 0%로 두면 "다 틀렸다"로 읽힌다 */
function valueFor(row: LevelRow): string {
  return row.percent === null ? "기록 없음" : `${row.percent}%`;
}

function useLevelDashboard() {
  const { data, isPending, isError, isFetching, refetch } = useQuery({
    queryKey: ["levelStats"],
    queryFn: learningService.getLevelStats,
  });
  return {
    dashboard: selectLevelDashboard(data),
    // 실패 뒤 다시 시도하는 동안에도 isPending은 false다 — 그 사이도 불러오는 중으로 보여준다
    loading: isPending || (isError && isFetching),
    isError,
    refetch,
  };
}

/** V26 내 실력 (음성 중심) — 약한 단계 세 개만 크게 */
function VoiceSkills() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { dashboard, loading, isError, refetch } = useLevelDashboard();
  const weakest = weakestLevels(dashboard.rows);

  useEffect(() => {
    if (loading) return;
    // 실패를 "기록 없음"으로 말하면 학습한 사람에게 기록이 사라진 것처럼 들린다
    if (isError) {
      announce(LOAD_FAILED_SPEECH);
      return;
    }
    if (weakest.length === 0) {
      announce("내 실력이에요. 아직 기록이 없어요. 학습을 시작하면 단계별 정답률을 알려드려요.");
      return;
    }
    announce(
      `내 실력이에요. 전체 ${dashboard.overallPercent}퍼센트. 더 연습할 것부터 알려드려요. ${weakest
        .map(speechFor)
        .join(". ")}`,
    );
  }, [loading, isError, weakest, dashboard.overallPercent]);

  return (
    <ScreenShell>
      <BackHeader title="내 실력" />
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>
        {loading
          ? LOADING_TEXT
          : isError
            ? LOAD_FAILED_TEXT
            : weakest.length === 0
              ? EMPTY_TEXT
              : "더 연습할 것부터 보여드려요."}
      </Text>
      {isError && !loading && (
        <AppButton label="다시 시도" variant="secondary" onPress={() => void refetch()} />
      )}
      {weakest.map((row) => (
        <ListRow
          key={row.level}
          label={row.label}
          value={valueFor(row)}
          emphasizeValue={row.percent !== null && row.percent < NEEDS_PRACTICE_PERCENT}
          highlighted={row.percent !== null && row.percent < NEEDS_PRACTICE_PERCENT}
        />
      ))}
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label="약한 것부터 복습"
          onPress={() => router.push({ pathname: "/session/intro", params: { mode: "review" } })}
          accessibilityHint="더 연습이 필요한 것부터 복습을 시작해요"
        />
      </View>
    </ScreenShell>
  );
}

/** L26 내 실력 대시보드 (화면 중심) — 전체 게이지·통계·단계별 % */
function ScreenSkills() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { dashboard, loading, isError, refetch } = useLevelDashboard();

  useEffect(() => {
    // 응답 전에는 아무것도 말하지 않는다. 예전에는 빈 값으로 계산한 "아직 기록이 없어요"를
    // 화면에 그리고 읽기까지 해서, 기록이 있는 학습자도 잠깐 기록이 없다고 들었다
    if (loading) return;
    if (isError) {
      announce(LOAD_FAILED_SPEECH);
      return;
    }
    // 기록이 없으면 정답률을 말하지 않는다 — 0%는 "다 틀렸다"로 들린다
    announce(
      dashboard.overallPercent === null
        ? "내 실력이에요. 아직 기록이 없어요."
        : `내 실력이에요. 전체 ${dashboard.overallPercent}퍼센트. ${dashboard.totalSolved}문제 중 ${dashboard.totalCorrect}개 맞혔어요.`,
    );
  }, [dashboard, loading, isError]);

  if (loading || isError) {
    return (
      <ScreenShell>
        <BackHeader title="내 실력" />
        <Text
          accessibilityLiveRegion="polite"
          style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}
        >
          {loading ? LOADING_TEXT : LOAD_FAILED_TEXT}
        </Text>
        {!loading && (
          <AppButton label="다시 시도" variant="secondary" onPress={() => void refetch()} />
        )}
      </ScreenShell>
    );
  }

  const overallDetail =
    dashboard.overallPercent === null
      ? "아직 기록이 없어요"
      : `${dashboard.totalSolved}문제 중 ${dashboard.totalCorrect}개 맞혔어요`;

  return (
    <ScreenShell>
      <BackHeader title="내 실력" />
      <ScrollView contentContainerStyle={{ gap: 14, paddingBottom: 10 }} showsVerticalScrollIndicator={false}>
        <View
          style={[
            styles.hero,
            { backgroundColor: theme.color.primary, borderRadius: radius.card },
            theme.shadow != null && { boxShadow: `0 6px 0 ${theme.shadow.primary}` },
          ]}
          accessibilityLabel={
            dashboard.overallPercent === null
              ? "아직 기록이 없어요"
              : `전체 정답률 ${dashboard.overallPercent}퍼센트. ${overallDetail}`
          }
        >
          <Text style={{ fontSize: 44, fontWeight: "700", color: theme.color.onPrimary, fontVariant: ["tabular-nums"] }}>
            {dashboard.overallPercent === null ? "—" : `${dashboard.overallPercent}%`}
          </Text>
          <Text style={{ fontSize: 14 * fontScale, fontWeight: "600", color: theme.color.onPrimary }}>
            {overallDetail}
          </Text>
        </View>
        {/*
          단계는 여섯 개뿐이라 전부 그린다. 요소별(74개)이었을 때는 다 그리면 읽을 수 없어
          약한 것과 잘하는 것만 잘라 보여줬는데, 그러면 학습자가 자기 단계를 찾지 못했다.
        */}
        <View style={{ gap: 8 }}>
          {dashboard.rows.map((row) => (
            <View
              key={row.level}
              style={[styles.levelRow, { borderColor: theme.color.border, borderRadius: radius.control }]}
              accessibilityLabel={
                row.percent === null
                  ? `${row.label}, 아직 안 풀었어요`
                  : `${row.label}, ${row.percent}퍼센트. ${row.solvedCount}문제 중 ${row.correctCount}개 맞힘`
              }
            >
              <Text style={{ flex: 1, fontSize: 16 * fontScale, fontWeight: "700", color: theme.color.text }}>
                {row.label}
              </Text>
              {row.percent !== null ? (
                <View style={styles.gaugeWrap}>
                  <View style={[styles.gauge, { backgroundColor: theme.color.surface }]}>
                    <View
                      style={[
                        styles.gaugeFill,
                        {
                          width: `${row.percent}%`,
                          backgroundColor:
                            row.percent < NEEDS_PRACTICE_PERCENT ? theme.color.text : theme.color.primary,
                        },
                      ]}
                    />
                  </View>
                  <Text
                    style={{
                      fontSize: 15 * fontScale,
                      fontWeight: "800",
                      color: theme.color.text,
                      fontVariant: ["tabular-nums"],
                      width: 44,
                      textAlign: "right",
                    }}
                  >
                    {row.percent}%
                  </Text>
                </View>
              ) : (
                <Text style={{ fontSize: 14 * fontScale, color: theme.color.textMuted }}>아직 안 풀었어요</Text>
              )}
            </View>
          ))}
        </View>
      </ScrollView>
      <AppButton
        label="약한 것부터 복습"
        onPress={() => router.push({ pathname: "/session/intro", params: { mode: "review" } })}
      />
    </ScreenShell>
  );
}

export default function Skills() {
  const mode = useSettingsStore((s) => s.mode);
  return mode === "screen" ? <ScreenSkills /> : <VoiceSkills />;
}

const styles = StyleSheet.create({
  hero: { padding: 22, alignItems: "center", gap: 4 },
  levelRow: {
    borderWidth: 1.5,
    paddingHorizontal: 14,
    paddingVertical: 12,
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  gaugeWrap: { flexDirection: "row", alignItems: "center", gap: 8, width: 150 },
  gauge: { flex: 1, height: 10, borderRadius: 5, overflow: "hidden" },
  gaugeFill: { height: 10, borderRadius: 5 },
});
