import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { useSessionStore } from "@/stores/sessionStore";
import { learningService } from "@/services/learningService";
import { selectSessionSummaryFeedback } from "@/services/sessionSummaryFeedback";
import { announce } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";
import type { ProficiencyLevel } from "@ieum/contracts";

const levelLabel: Record<ProficiencyLevel, string> = {
  familiar: "익숙해요",
  learning: "배우는 중",
  "needs-practice": "더 연습해요",
};

/** V18 학습 결과 */
export default function Summary() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const serverResult = useSessionStore((s) => s.serverResult);
  const localCorrect = useSessionStore((s) => s.correctCount)();
  // 서버 채점이 최종이다. 로컬 채점은 문항별 즉시 반응에만 쓴 잠정 값이라 결과에 쓰지 않는다
  const correct = serverResult?.correctProblemCount ?? localCorrect;
  const total = serverResult?.totalProblemCount ?? session?.problems.length ?? 0;
  const reset = useSessionStore((s) => s.reset);
  const { data: skills } = useQuery({ queryKey: ["skills"], queryFn: learningService.getSkillLevels });
  // 서버가 세션 요약을 못 만들었거나(notAvailable) 목 세션이면 lines가 비어 있다 —
  // 그때는 지금과 같은 화면을 그대로 보여준다.
  //
  // 점수 문장은 여기(앱)가 책임진다. `selectSessionSummaryFeedback`이 서버 문구에서 점수
  // 부분을 떼고 조언만 남기므로, 한 화면에서 점수는 아래 `scoreAnnouncement` 한 번,
  // 그것도 화면에 보이는 `correct` / `total`과 같은 숫자로만 나온다
  const summaryFeedback = selectSessionSummaryFeedback(serverResult);

  useEffect(() => {
    if (total === 0) return;
    // 점수 안내와 세션 요약을 한 번에 낭독한다 — announce는 이전 낭독을 끊고 새로 시작하므로
    // 따로 두 번 부르면 앞 문장이 사라진다
    const scoreAnnouncement = `오늘의 결과예요. ${total}문제 중 ${correct}문제를 맞혔어요.`;
    announce(summaryFeedback.speech ? `${scoreAnnouncement} ${summaryFeedback.speech}` : scoreAnnouncement);
  }, [correct, total, summaryFeedback.speech]);

  const finish = (to: "/(tabs)" | "/(tabs)/learn") => {
    reset();
    router.replace(to);
  };

  return (
    <ScreenShell>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        오늘의 결과
      </Text>
      <View
        style={[styles.score, { backgroundColor: theme.color.primary, borderRadius: radius.card }]}
        accessibilityLabel={`이번 세션 정답 ${total}문제 중 ${correct}문제`}
      >
        <Text style={{ fontSize: 44, fontWeight: "700", color: theme.color.onPrimary, fontVariant: ["tabular-nums"] }}>
          {correct} / {total}
        </Text>
        <Text style={{ fontSize: 16 * fontScale, fontWeight: "700", color: theme.color.onPrimary }}>이번 세션 정답</Text>
      </View>
      {summaryFeedback.lines.length > 0 && (
        <View style={[styles.summary, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
          {summaryFeedback.lines.map((line) => (
            <Text key={line} style={{ fontSize: 16 * fontScale, fontWeight: "700", color: theme.color.text, lineHeight: 24 }}>
              {line}
            </Text>
          ))}
        </View>
      )}
      <View style={[styles.skills, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
        <Text style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.textMuted }}>지금 내 실력 (현재 기준)</Text>
        {(skills ?? []).map((skill) => (
          <View key={skill.label} style={styles.skillRow} accessibilityLabel={`${skill.label}, ${levelLabel[skill.level]}`}>
            <Text style={{ fontSize: 17 * fontScale, fontWeight: "700", color: theme.color.text }}>{skill.label}</Text>
            <Text
              style={
                skill.level === "needs-practice"
                  ? { fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.text, backgroundColor: theme.color.border, borderRadius: 6, paddingHorizontal: 10, paddingVertical: 2, overflow: "hidden" }
                  : { fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.primary }
              }
            >
              {levelLabel[skill.level]}
            </Text>
          </View>
        ))}
      </View>
      <AppButton label="내 실력 자세히 보기" variant="compact" onPress={() => router.push("/tools/skills")} />
      <View style={{ marginTop: "auto", gap: 12 }}>
        <AppButton label="다음 학습 시작" onPress={() => finish("/(tabs)/learn")} />
        <AppButton label="홈으로" variant="secondary" onPress={() => finish("/(tabs)")} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  score: { padding: 22, alignItems: "center", gap: 6 },
  summary: { borderWidth: 1, padding: 18, gap: 8 },
  skills: { borderWidth: 1, padding: 18, gap: 12 },
  skillRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
});
