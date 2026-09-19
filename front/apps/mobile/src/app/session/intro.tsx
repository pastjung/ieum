import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import { useSessionStore } from "@/stores/sessionStore";
import { announce } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";
import type { LearningThemeId } from "@/services/mockData";

const rules = [
  ["입력", "점을 동시에 짚고 손을 떼면 한 칸"],
  ["삭제", "지우기 버튼으로 마지막 칸 삭제"],
  ["입력 완료", "하단 버튼을 길게 누르기"],
  ["다시 듣기", "문제를 한 번 더 읽어 줘요"],
] as const;

/** V13 세션 안내 */
export default function SessionIntro() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { theme: themeId } = useLocalSearchParams<{ theme?: LearningThemeId }>();
  const start = useSessionStore((s) => s.start);
  const { data: session } = useQuery({
    queryKey: ["freeSession", themeId ?? "default"],
    queryFn: () => learningService.getFreeSession(themeId),
  });

  useEffect(() => {
    if (!session) return;
    announce(
      `${session.title}. ${session.problems.length}문항이에요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요. 시작하기를 누르면 시작해요.`,
    );
  }, [session]);

  return (
    <ScreenShell>
      <BackHeader title={session?.title ?? "학습 준비 중"} />
      <Text style={{ fontSize: 18 * fontScale, color: theme.color.textMuted }}>
        {session ? `${session.problems.length}문항 · 한 문제씩 천천히 풀어요` : "불러오는 중이에요…"}
      </Text>
      <View style={[styles.card, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
        {rules.map(([k, v]) => (
          <Text key={k} style={{ fontSize: 17 * fontScale, lineHeight: 27, color: theme.color.text }}>
            <Text style={{ fontWeight: "800", color: theme.color.primary }}>{k}</Text> — {v}
          </Text>
        ))}
      </View>
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label="시작하기"
          disabled={!session}
          onPress={() => {
            if (!session) return;
            start(session);
            router.replace("/session/play");
          }}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, padding: 20, gap: 14 },
});
