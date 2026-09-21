import { useEffect } from "react";
import { ScrollView, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { ListRow } from "@/components/ListRow";
import { learningThemes, type LearningThemeId } from "@/services/mockData";
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

  const start = (id: LearningThemeId) => {
    void hapticSelect();
    router.push({ pathname: "/session/intro", params: { theme: id } });
  };

  return (
    <ScreenShell>
      <BackHeader title="테마 선택" />
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted }}>원하는 테마를 골라 시작해요.</Text>
      <ScrollView contentContainerStyle={{ gap: 12, paddingBottom: 10 }} showsVerticalScrollIndicator={false}>
        {learningThemes.map((t) => {
          const percent = t.solved > 0 ? Math.round((t.correct / t.solved) * 100) : null;
          return (
            <ListRow
              key={t.id}
              label={t.label}
              description={t.solved > 0 ? `${t.solved}문제 중 ${t.correct}개 맞힘` : "아직 안 풀었어요"}
              value={percent != null ? `${percent}%` : "—"}
              onPress={() => start(t.id)}
              accessibilityHint="누르면 이 테마로 학습을 시작해요"
            />
          );
        })}
      </ScrollView>
      <View style={{ marginTop: "auto" }}>
        <AppButton label="모음 이어서 하기" onPress={() => start("vowel")} />
      </View>
    </ScreenShell>
  );
}
