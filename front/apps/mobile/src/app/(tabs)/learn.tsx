import { useEffect } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { learningThemes, type LearningThemeId } from "@/services/mockData";
import { announce, hapticSelect } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V12 테마 선택 · 자유 학습 */
export default function Learn() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    announce("테마 선택이에요. 자음, 모음, 약자, 단어, 약어, 문장 중에 골라 시작해요.");
  }, []);

  const start = (id: LearningThemeId) => {
    void hapticSelect();
    router.push({ pathname: "/session/intro", params: { theme: id } });
  };

  return (
    <ScreenShell noBottomPadding>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        테마 선택
      </Text>
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted }}>원하는 테마를 골라 시작해요.</Text>
      <View style={styles.grid}>
        {learningThemes.map((t) => (
          <Pressable
            key={t.id}
            accessibilityRole="button"
            accessibilityLabel={`${t.label} 학습 시작`}
            onPress={() => start(t.id)}
            style={({ pressed }) => [
              styles.tile,
              { borderColor: theme.color.border, borderRadius: radius.card },
              pressed && { opacity: 0.7, borderColor: theme.color.primary },
            ]}
          >
            <Text style={{ fontSize: 32 * fontScale, fontWeight: "900", color: theme.color.primary }}>{t.label}</Text>
          </Pressable>
        ))}
      </View>
      <View style={{ paddingBottom: 14, gap: 10 }}>
        <AppButton label="점자 사전" variant="compact" onPress={() => router.push("/tools/dictionary")} accessibilityHint="단어의 점자를 찾아봐요" />
        <AppButton label="모음 이어서 하기" onPress={() => start("vowel")} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  grid: {
    flex: 1,
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
  },
  tile: {
    borderWidth: 2,
    alignItems: "center",
    justifyContent: "center",
    flexBasis: "47%",
    flexGrow: 1,
    minHeight: 96,
  },
});
