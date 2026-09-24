import { useCallback, useEffect, useState } from "react";
import { StyleSheet, Text, View, useWindowDimensions } from "react-native";
import { useRouter } from "expo-router";
import { cellToDots, cellsToUnicode, type Cell } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { LandscapeDotPad } from "@/components/LandscapeDotPad";
import { AppButton } from "@/components/AppButton";
import { useSessionStore } from "@/stores/sessionStore";
import { useSettingsStore } from "@/stores/settingsStore";
import { describeTooManyCells } from "@/services/answerLengthGuard";
import { announce, hapticError, hapticTap, speak } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V14 문제 풀이 · 세로 V패드 / V14b 가로 두 손 입력 (기기를 돌리면 전환) */
export default function SessionPlay() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { width, height } = useWindowDimensions();
  const isLandscape = width > height;
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const submit = useSessionStore((s) => s.submit);
  const problem = useSessionStore((s) => s.current)();
  const isScreenMode = useSettingsStore((s) => s.mode) === "screen";
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
    return (
      <ScreenShell style={styles.center}>
        <Text style={{ color: theme.color.text, fontSize: 20, fontWeight: "700" }}>진행 중인 학습이 없어요</Text>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  /** 제출 버튼 짧은 터치: 지금까지 입력한 점을 읽어 준다 */
  const readInput = () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요. 점을 짚고 손을 떼면 한 칸이 입력돼요.");
      return;
    }
    const last = cellToDots(cells[cells.length - 1]!);
    announce(
      `지금까지 ${cells.length}칸 입력했어요. 마지막 칸은 ${last.join("번, ")}번 점이에요. 길게 누르면 제출돼요.`,
    );
  };

  const submitAnswer = () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요. 점을 짚고 손을 떼면 한 칸이 입력돼요.");
      return;
    }
    // 서버는 칸 수가 정확히 맞아야 답을 받는다. 넘친 채로 보내면 `buildAttempts`가 잘라서
    // 보내게 되는데, `gradeAnswer`는 넘친 답을 오답으로 판정하므로 화면과 서버 채점이 갈라진다
    // — 문항마다 "틀렸어요"를 듣고 결과 화면에서는 만점을 보게 된다. 여기서 막는다.
    // 모자란 답은 그대로 보낸다 — 빈 칸으로 채워도 양쪽 다 오답이라 어긋나지 않는다.
    const tooMany = describeTooManyCells(cells.length, problem.expectedCells.length);
    if (tooMany) {
      void hapticError();
      announce(tooMany);
      return;
    }
    submit(cells);
    router.replace("/session/grade");
  };

  const eraseLast = () =>
    setCells((prev) => {
      if (prev.length === 0) {
        announce("지울 칸이 없어요.");
        return prev;
      }
      announce("한 칸 지웠어요.");
      return prev.slice(0, -1);
    });

  // V14b 가로 두 손 입력 — 왼손 3·2·1, 오른손 6·5·4
  if (isLandscape) {
    return (
      <ScreenShell>
        <View style={styles.topRow}>
          <Text accessibilityRole="header" style={{ fontSize: 17 * fontScale, fontWeight: "800", color: theme.color.text, flexShrink: 1 }}>
            {problem.prompt}
          </Text>
          <Text
            accessibilityLabel={`${session.problems.length}문제 중 ${index + 1}번`}
            style={{ fontSize: 18, fontWeight: "700", color: theme.color.text, fontVariant: ["tabular-nums"] }}
          >
            {index + 1} / {session.problems.length}
          </Text>
          <AppButton label="나가기" variant="compact" accessibilityHint="학습을 중단하고 홈으로 가요" onPress={() => router.replace("/(tabs)")} />
        </View>
        <LandscapeDotPad
          onCommitCell={commit}
          center={
            <>
              <View style={{ flex: 1 }}>
                <AppButton label={"다시\n듣기"} variant="compact" onPress={() => speak(problem.prompt, { force: true })} />
              </View>
              <View style={{ flex: 1 }}>
                <AppButton label="지우기" variant="compact" onPress={eraseLast} />
              </View>
              <View style={{ flex: 1.4 }}>
                <AppButton label={"입력\n완료"} requireLongPress onPress={submitAnswer} onShortPress={readInput} />
              </View>
            </>
          }
        />
        <Text style={{ fontSize: 13, color: theme.color.textMuted, textAlign: "center" }}>
          양손 파지 — 왼손 3·2·1, 오른손 6·5·4
        </Text>
      </ScreenShell>
    );
  }

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
          accessibilityLabel={`지금까지 ${cells.length}칸 입력. 기기를 가로로 돌리면 두 손 입력이 돼요.`}
          style={{ fontSize: 18, color: theme.color.textMuted }}
        >
          {cells.length > 0 ? `${cellsToUnicode(cells)} · ${cells.length}칸` : "입력 전"}
        </Text>
      </View>
      <Text accessibilityRole="header" style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        {problem.prompt}
      </Text>
      {isScreenMode && (
        <View
          style={{
            minHeight: 64,
            borderWidth: 2,
            borderColor: theme.color.border,
            borderRadius: 14,
            alignItems: "center",
            justifyContent: "center",
            paddingHorizontal: 12,
          }}
          accessibilityLabel={`입력한 점자 ${cells.length}칸`}
        >
          <Text style={{ fontSize: 40, color: theme.color.text, letterSpacing: 6 }}>
            {cells.length > 0 ? cellsToUnicode(cells) : "⠀"}
          </Text>
        </View>
      )}
      <View style={{ flexDirection: "row", gap: 10 }}>
        <View style={{ flex: 1 }}>
          <AppButton label="문제 다시 듣기" variant="compact" onPress={() => speak(problem.prompt, { force: true })} />
        </View>
        <View style={{ flex: 1 }}>
          <AppButton label="한 칸 지우기" variant="compact" accessibilityHint="마지막에 입력한 칸을 지워요" onPress={eraseLast} />
        </View>
      </View>
      <DotPad onCommitCell={commit} />
      <AppButton label="길게 눌러 제출" requireLongPress onPress={submitAnswer} onShortPress={readInput} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 16 },
  topRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 10 },
});
