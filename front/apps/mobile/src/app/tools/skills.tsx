import { useEffect } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import {
  selectSkillDashboard,
  skillDisplayName,
  type SkillDashboard,
} from "@/services/skillDashboard";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** 서버가 라벨 문자열을 그대로 준다. 기준(label_of)은 FastAPI에만 있다 */
const NEEDS_PRACTICE_LABEL = "더 연습해요";

/** 음성 중심 화면이 한 번에 보여줄 요소 수. 목록이 길수록 탐색이 어렵다 */
const VOICE_SKILL_LIMIT = 3;

/** 비어 있는 구역은 제목도 내지 않는다 — 제목만 있고 줄이 없으면 불러오다 만 것처럼 보인다 */
function ruleSections(d: SkillDashboard) {
  return [
    { title: "더 연습할 것", rows: d.weakest },
    { title: "잘하고 있어요", rows: d.strongest },
  ].filter((section) => section.rows.length > 0);
}

/** V26 내 실력 (음성 중심) — 핵심 3요소만 크게 */
function VoiceSkills() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { data: summary, isPending } = useQuery({
    queryKey: ["skillSummary"],
    queryFn: learningService.getSkillSummary,
  });
  // 약한 것부터 정렬돼 오므로 앞에서 끊는다. 음성 중심 화면은 목록이 길수록 탐색이 어렵다
  const skills = summary?.skills.slice(0, VOICE_SKILL_LIMIT) ?? [];

  useEffect(() => {
    if (isPending) return;
    if (skills.length === 0) {
      announce("내 실력이에요. 아직 기록이 없어요. 학습을 시작하면 요소별 실력을 알려드려요.");
      return;
    }
    const spoken = skills.map((s) => `${skillDisplayName(s)}, ${s.label}`).join(". ");
    announce(`내 실력이에요. 더 연습할 것부터 알려드려요. ${spoken}`);
  }, [isPending, skills]);

  return (
    <ScreenShell>
      <BackHeader title="내 실력" />
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>
        {isPending
          ? "불러오는 중이에요…"
          : skills.length === 0
            ? "아직 기록이 없어요. 학습을 시작하면 알려드려요."
            : "더 연습할 것부터 보여드려요."}
      </Text>
      {skills.map((skill) => (
        <ListRow
          key={skill.learningSkillId}
          label={skillDisplayName(skill)}
          value={skill.label}
          emphasizeValue={skill.label === NEEDS_PRACTICE_LABEL}
          highlighted={skill.label === NEEDS_PRACTICE_LABEL}
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
  const { data: summary } = useQuery({
    queryKey: ["skillSummary"],
    queryFn: learningService.getSkillSummary,
  });
  const d = selectSkillDashboard(summary);

  useEffect(() => {
    // 기록이 없으면 숙련도를 말하지 않는다 — 0%는 "다 틀렸다"로 들린다
    announce(
      d.overallPercent === null
        ? "내 실력이에요. 아직 기록이 없어요."
        : `내 실력이에요. 전체 ${d.overallPercent}퍼센트. ${d.overallDetail}.`,
    );
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
          accessibilityLabel={
            d.overallPercent === null
              ? "아직 기록이 없어요"
              : `전체 진행 ${d.overallPercent}퍼센트. ${d.overallDetail}`
          }
        >
          <Text style={{ fontSize: 44, fontWeight: "700", color: theme.color.onPrimary, fontVariant: ["tabular-nums"] }}>
            {d.overallPercent === null ? "—" : `${d.overallPercent}%`}
          </Text>
          <Text style={{ fontSize: 14 * fontScale, fontWeight: "600", color: theme.color.onPrimary }}>
            {d.overallDetail ?? "아직 기록이 없어요"}
          </Text>
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
        {/*
          요소 74개를 다 그리면 읽을 수 없고, 분류로 묶으면 "단독 자모 35개 평균 12%"처럼
          무엇을 연습해야 할지 알 수 없는 줄이 된다. 약한 것과 잘하는 것만 몇 개씩 보여준다.
        */}
        {ruleSections(d).map((section) => (
          <View key={section.title} style={{ gap: 8 }}>
            <Text style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.textMuted }}>
              {section.title}
            </Text>
            {section.rows.map((r) => (
            <View
              key={r.learningSkillId}
              style={[styles.ruleRow, { borderColor: theme.color.border, borderRadius: radius.control }]}
              accessibilityLabel={`${r.label}, ${r.level}, ${r.percent != null ? `${r.percent}퍼센트` : "기록 없음"}`}
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
        ))}
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
