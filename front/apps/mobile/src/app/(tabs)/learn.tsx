import { useEffect } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { ListRow } from "@/components/ListRow";
import { learningService } from "@/services/learningService";
import { selectThemeRows, type ThemeRow } from "@/services/themeStats";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce, hapticSelect } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V12 테마 선택 그리드 (음성 중심의 학습 탭) */
function VoiceLearn() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    announce("테마 선택이에요. 자음, 모음, 약자, 단어, 약어, 문장 중에 골라 시작해요.");
  }, []);

  const { data: stats } = useQuery({
    queryKey: ["stageStats"],
    queryFn: learningService.getStageStats,
  });
  const rows = selectThemeRows(stats);

  const start = (row: ThemeRow) => {
    void hapticSelect();
    router.push({
      pathname: "/session/intro",
      params: { curriculumId: String(row.curriculumStageId), title: row.label },
    });
  };

  return (
    <ScreenShell noBottomPadding>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        테마 선택
      </Text>
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted }}>원하는 테마를 골라 시작해요.</Text>
      <View style={styles.grid}>
        {rows.map((t) => (
          <Pressable
            key={t.curriculumStageId}
            accessibilityRole="button"
            accessibilityLabel={`${t.label} 학습 시작`}
            onPress={() => start(t)}
            style={({ pressed }) => [
              styles.tile,
              { borderColor: theme.color.border, borderRadius: radius.card },
              theme.shadow != null && { boxShadow: `0 4px 0 ${theme.shadow.control}` },
              pressed && { opacity: 0.7, borderColor: theme.color.primary, boxShadow: "none" },
            ]}
          >
            <Text style={{ fontSize: 32 * fontScale, fontWeight: "900", color: theme.color.primary }}>{t.label}</Text>
          </Pressable>
        ))}
      </View>
      <View style={{ paddingBottom: 14, gap: 10 }}>
        <AppButton label="점자 사전" variant="compact" onPress={() => router.push("/tools/dictionary")} accessibilityHint="단어의 점자를 찾아봐요" />
      </View>
    </ScreenShell>
  );
}

/** L10a 학습 메뉴 (화면 중심의 학습 탭) */
function ScreenLearn() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  return (
    <ScreenShell noBottomPadding>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        학습
      </Text>
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>무엇을 학습할까요</Text>
      <View style={{ gap: 12 }}>
        <ListRow
          label="자유 학습"
          description="내 단계에 맞는 문항 세션"
          chevron
          onPress={() => router.push("/session/intro")}
        />
        <ListRow
          label="복습 학습"
          description="약한 요소부터 다시 다져요"
          chevron
          onPress={() => router.push({ pathname: "/session/intro", params: { mode: "review" } })}
        />
        <ListRow
          label="테마 선택"
          description="배우고 싶은 테마로 바로 이동"
          chevron
          onPress={() => router.push("/learn-themes")}
        />
        <ListRow label="내 실력" description="요소별 숙련 상태 보기" chevron onPress={() => router.push("/tools/skills")} />
        <ListRow label="점자 사전" description="단어의 점자를 찾아봐요" chevron onPress={() => router.push("/tools/dictionary")} />
      </View>
    </ScreenShell>
  );
}

export default function Learn() {
  const mode = useSettingsStore((s) => s.mode);
  return mode === "screen" ? <ScreenLearn /> : <VoiceLearn />;
}

const styles = StyleSheet.create({
  grid: { flex: 1, flexDirection: "row", flexWrap: "wrap", gap: 12 },
  tile: {
    borderWidth: 2,
    alignItems: "center",
    justifyContent: "center",
    flexBasis: "47%",
    flexGrow: 1,
    minHeight: 96,
  },
});
