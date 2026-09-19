import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import type { Cell } from "@ieum/domain";
import { cellsToUnicode } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { AppButton } from "@/components/AppButton";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, speak } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V23 수업 진행 · 풀이 */
export default function ClassPlay() {
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
    if (problem) announce(`선생님 문제예요. ${problem.prompt}`);
  }, [problem]);

  if (!session || !problem) {
    return (
      <ScreenShell style={styles.center}>
        <Text style={{ color: theme.color.text, fontSize: 20, fontWeight: "700" }}>진행 중인 수업이 없어요</Text>
        <AppButton label="수업 참여로" onPress={() => router.replace("/(tabs)/class")} />
      </ScreenShell>
    );
  }

  const dot = (color: string) => (
    <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: color }} />
  );

  const submitAnswer = () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요.");
      return;
    }
    submit(cells);
    router.replace("/session/grade");
  };

  return (
    <ScreenShell>
      <View style={styles.statusRow}>
        <View style={styles.statusItem} accessibilityLabel="수업 중, 선생님 음성 연결됨">
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 16, fontWeight: "800", color: theme.color.text }}>수업 중</Text>
          <Text style={{ fontSize: 14, color: theme.color.textMuted, marginLeft: 6 }}>선생님 음성</Text>
        </View>
        <View
          style={[styles.chip, { borderColor: theme.color.border }]}
          accessibilityLabel="점자 장치 연결됨"
        >
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 14, fontWeight: "700", color: theme.color.text }}>장치 연결됨</Text>
        </View>
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
      <Text
        accessibilityLabel={`지금까지 ${cells.length}칸 입력`}
        style={{ fontSize: 16, color: theme.color.textMuted, textAlign: "center" }}
      >
        {cells.length > 0 ? `${cellsToUnicode(cells)} · ${cells.length}칸` : "입력 전"}
      </Text>
      <DotPad onCommitCell={(cell) => setCells((prev) => [...prev, cell])} />
      <AppButton label="답안 제출" onPress={submitAnswer} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 16 },
  statusRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  statusItem: { flexDirection: "row", alignItems: "center", gap: 6 },
  chip: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    borderWidth: 2,
    borderRadius: 99,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
});
