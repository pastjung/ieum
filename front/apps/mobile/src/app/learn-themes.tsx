import { useEffect } from "react";
import { ScrollView, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { ListRow } from "@/components/ListRow";
import { learningService } from "@/services/learningService";
import { selectThemeRows, type ThemeRow } from "@/services/themeStats";
import { announce, hapticSelect } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** L12 테마 선택 · 진행률 목록 (화면 중심) */
export default function LearnThemes() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    announce("테마 선택이에요. 테마별 진행률을 보고 골라 시작해요.");
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
    <ScreenShell>
      <BackHeader title="테마 선택" />
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted }}>원하는 테마를 골라 시작해요.</Text>
      <ScrollView contentContainerStyle={{ gap: 12, paddingBottom: 10 }} showsVerticalScrollIndicator={false}>
        {rows.map((row) => (
          <ListRow
            key={row.curriculumStageId}
            label={row.label}
            description={row.description}
            value={row.percent != null ? `${row.percent}%` : "—"}
            onPress={() => start(row)}
            accessibilityHint="누르면 이 테마로 학습을 시작해요"
          />
        ))}
      </ScrollView>
    </ScreenShell>
  );
}
