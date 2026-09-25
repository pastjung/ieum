import { useEffect } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import { skillDashboard } from "@/services/mockData";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";
import type { ProficiencyLevel } from "@ieum/contracts";

const levelLabel: Record<ProficiencyLevel, string> = {
  familiar: "익숙해요",
  learning: "배우는 중",
  "needs-practice": "더 연습해요",
};

/** V26 내 실력 (음성 중심) — 핵심 3요소만 크게 */
function VoiceSkills() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { data: skills } = useQuery({ queryKey: ["skills"], queryFn: learningService.getSkillLevels });

  useEffect(() => {
    if (!skills) return;
    const spoken = skills.map((s) => `${s.label}, ${levelLabel[s.level]}`).join(". ");
    announce(`내 실력이에요. 3단계 기준, 지금 실력이에요. ${spoken}`);
  }, [skills]);

  return (
    <ScreenShell>
      <BackHeader title="내 실력" />
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>3단계 기준, 지금 실력이에요.</Text>
      {(skills ?? []).map((skill) => (
        <ListRow
          key={skill.label}
          label={skill.label}
          value={levelLabel[skill.level]}
          emphasizeValue={skill.level === "needs-practice"}
          highlighted={skill.level === "needs-practice"}
        />
      ))}
      <View style={{ marginTop: "auto" }}>
        <AppButton label="약한 것부터 복습" onPress={() => router.push({ pathname: "/session/intro", params: { mode: "review" } })} accessibilityHint="더 연습이 필요한 것부터 복습을 시작해요" />
      </View>
    </ScreenShell>
  );
}

/** L26 내 실력 대시보드 (화면 중심) — 전체 게이지·통계·규칙별 % */
function ScreenSkills() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const d = skillDashboard;

  useEffect(() => {
    announce(`내 실력이에요. 전체 ${d.overallPercent}퍼센트, ${d.overallLabel}. ${d.overallDetail}.`);
  }, [d]);

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
          accessibilityLabel={`전체 진행 ${d.overallPercent}퍼센트. ${d.overallLabel}. ${d.overallDetail}`}
        >
          <Text style={{ fontSize: 44, fontWeight: "700", color: theme.color.onPrimary, fontVariant: ["tabular-nums"] }}>
            {d.overallPercent}%
          </Text>
          <Text style={{ fontSize: 18 * fontScale, fontWeight: "900", color: theme.color.onPrimary }}>{d.overallLabel}</Text>
          <Text style={{ fontSize: 14 * fontScale, fontWeight: "600", color: theme.color.onPrimary }}>{d.overallDetail}</Text>
        </View>
        <View style={styles.statRow}>
          {d.stats.map((s) => (
            <View
              key={s.label}
              style={[
                styles.stat,
                { backgroundColor: theme.color.surface, borderRadius: radius.control },
                theme.shadow != null && { boxShadow: `0 4px 0 ${theme.shadow.control}` },
              ]}
              accessibilityLabel={`${s.label} ${s.value}`}
            >
              <Text style={{ fontSize: 20 * fontScale, fontWeight: "900", color: theme.color.text, fontVariant: ["tabular-nums"] }}>
                {s.value}
              </Text>
              <Text style={{ fontSize: 13 * fontScale, color: theme.color.textMuted }}>{s.label}</Text>
            </View>
          ))}
        </View>
        <Text style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.textMuted }}>
          규칙별 실력 · {d.rules.length}개 규칙
        </Text>
        <View style={{ gap: 8 }}>
          {d.rules.map((r) => (
            <View
              key={r.label}
              style={[styles.ruleRow, { borderColor: theme.color.border, borderRadius: radius.control }]}
              accessibilityLabel={`${r.label}, ${r.percent != null ? `${r.percent}퍼센트` : "기록 없음"}`}
            >
              <Text style={{ flex: 1, fontSize: 16 * fontScale, fontWeight: "700", color: theme.color.text }}>{r.label}</Text>
              {r.percent != null ? (
                <View style={styles.gaugeWrap}>
                  <View style={[styles.gauge, { backgroundColor: theme.color.surface }]}>
                    <View
                      style={[
                        styles.gaugeFill,
                        { width: `${r.percent}%`, backgroundColor: r.percent < 60 ? theme.color.text : theme.color.primary },
                      ]}
                    />
                  </View>
                  <Text style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.text, fontVariant: ["tabular-nums"], width: 44, textAlign: "right" }}>
                    {r.percent}%
                  </Text>
                </View>
              ) : (
                <Text style={{ fontSize: 14 * fontScale, color: theme.color.textMuted }}>기록 없음</Text>
              )}
            </View>
          ))}
        </View>
      </ScrollView>
      <AppButton label="약한 것부터 복습" onPress={() => router.push({ pathname: "/session/intro", params: { mode: "review" } })} />
    </ScreenShell>
  );
}

export default function Skills() {
  const mode = useSettingsStore((s) => s.mode);
  return mode === "screen" ? <ScreenSkills /> : <VoiceSkills />;
}

const styles = StyleSheet.create({
  hero: { padding: 22, alignItems: "center", gap: 4 },
  statRow: { flexDirection: "row", gap: 10 },
  stat: { flex: 1, alignItems: "center", paddingVertical: 14, gap: 2 },
  ruleRow: {
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
