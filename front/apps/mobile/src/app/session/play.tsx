import { useCallback, useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { cellsToUnicode, type Cell } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { AppButton } from "@/components/AppButton";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticTap, speak } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V14 문제 풀이 · 세로 V패드 */
export default function SessionPlay() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const submit = useSessionStore((s) => s.submit);
  const problem = useSessionStore((s) => s.current)();
  const [cells, setCells] = useState<Cell[]>([]);

  useEffect(() => {
    setCells([]);
    if (problem) announce(`${index + 1}번 문제. ${problem.prompt}`);
  }, [problem, index]);

  const commit = useCallback((cell: Cell) => {
    setCells((prev) => [...prev, cell]);
    void hapticTap();
  }, []);

  if (!session || !problem) {
    // 세션 없이 직접 진입한 경우 안내 화면으로
    return (
      <ScreenShell style={styles.center}>
        <Text style={{ color: theme.color.text, fontSize: 20, fontWeight: "700" }}>진행 중인 학습이 없어요</Text>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  const submitAnswer = () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요. 점을 짚고 손을 떼면 한 칸이 입력돼요.");
      return;
    }
    submit(cells);
    router.replace("/session/grade");
  };

  return (
    <ScreenShell>
      <View style={styles.topRow}>
        <AppButton label="나가기" variant="compact" accessibilityHint="학습을 중단하고 홈으로 가요" onPress={() => router.replace("/(tabs)")} />
        <Text
          accessibilityLabel={`${session.problems.length}문제 중 ${index + 1}번`}
          style={{ fontSize: 22, fontWeight: "700", color: theme.color.text, fontVariant: ["tabular-nums"] }}
        >
          {index + 1} / {session.problems.length}
        </Text>
        <Text
          accessibilityLabel={`지금까지 ${cells.length}칸 입력`}
          style={{ fontSize: 18, color: theme.color.textMuted }}
        >
          {cells.length > 0 ? `${cellsToUnicode(cells)} · ${cells.length}칸` : "입력 전"}
        </Text>
      </View>
      <Text accessibilityRole="header" style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        {problem.prompt}
      </Text>
      <View style={{ flexDirection: "row", gap: 10 }}>
        <View style={{ flex: 1 }}>
          <AppButton label="문제 다시 듣기" variant="compact" onPress={() => speak(problem.prompt, { force: true })} />
        </View>
        <View style={{ flex: 1 }}>
          <AppButton
            label="한 칸 지우기"
            variant="compact"
            accessibilityHint="마지막에 입력한 칸을 지워요"
            onPress={() =>
              setCells((prev) => {
                if (prev.length === 0) {
                  announce("지울 칸이 없어요.");
                  return prev;
                }
                announce("한 칸 지웠어요.");
                return prev.slice(0, -1);
              })
            }
          />
        </View>
      </View>
      <DotPad onCommitCell={commit} />
      <AppButton label="길게 눌러 제출" requireLongPress onPress={submitAnswer} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 16 },
  topRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 10 },
});
