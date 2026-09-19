import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { describeDiff, ordinalKo } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V15 채점 정답 · V16 채점 오답+차이 */
export default function Grade() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const result = useSessionStore((s) => s.lastResult);
  const next = useSessionStore((s) => s.next);
  const answers = useSessionStore((s) => s.answers);
  const kind = useSessionStore((s) => s.kind);
  const disconnectShown = useSessionStore((s) => s.disconnectShown);
  const markDisconnectShown = useSessionStore((s) => s.markDisconnectShown);

  const diffText = result ? describeDiff(result) : null;

  useEffect(() => {
    if (!result) return;
    if (result.correct) {
      void hapticSuccess();
      announce("정답이에요!");
    } else if (diffText) {
      void hapticError();
      announce(`오답이에요. ${diffText.headline}. ${diffText.detail}`);
    }
  }, [result, diffText]);

  if (!session || !result) {
    return (
      <ScreenShell style={styles.center}>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  const progress = `${index + 1} / ${session.problems.length}`;
  const lastAnswer = answers[answers.length - 1];

  const goNext = () => {
    if (next()) {
      // 수업 데모: 두 번째 문항 뒤 연결 끊김(V29)을 한 번 보여 준다 — 실연동 시 실제 이벤트로 교체
      if (kind === "class" && !disconnectShown && index + 1 === 2) {
        markDisconnectShown();
        router.replace("/class/disconnected");
        return;
      }
      router.replace(kind === "class" ? "/class/play" : "/session/play");
    } else {
      router.replace(kind === "class" ? "/class/end" : "/session/analyzing");
    }
  };

  return (
    <ScreenShell>
      <Text style={{ fontSize: 18, fontWeight: "700", color: theme.color.textMuted, fontVariant: ["tabular-nums"] }}>
        {progress}
        {result.correct ? "" : " · 오답"}
      </Text>
      {result.correct ? (
        <View style={styles.center}>
          <View style={[styles.badge, { backgroundColor: theme.color.primary }]} accessibilityLabel="정답 캐릭터" />
          <Text accessibilityRole="header" style={{ fontSize: 40 * fontScale, fontWeight: "900", color: theme.color.primary }}>
            정답이에요!
          </Text>
        </View>
      ) : (
        <>
          <Text accessibilityRole="header" style={{ fontSize: 32 * fontScale, fontWeight: "900", lineHeight: 44, color: theme.color.primary }}>
            {diffText?.headline}
          </Text>
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 28 }}>{diffText?.detail}</Text>
          {result.firstDiff && (
            <View style={[styles.compare, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
              <BrailleCellView
                cell={result.firstDiff.expected}
                caption={`정답 · ${ordinalKo(result.firstDiff.index)} 칸`}
                accessibilityLabel={`정답 점자, ${ordinalKo(result.firstDiff.index)} 칸`}
              />
              <BrailleCellView
                cell={result.firstDiff.actual}
                emphasized
                markDots={result.firstDiff.missing}
                caption={
                  result.firstDiff.missing.length > 0
                    ? `내 입력 · ${result.firstDiff.missing.join("·")}번 누락`
                    : `내 입력 · ${result.firstDiff.extra.join("·")}번 초과`
                }
                accessibilityLabel="내가 입력한 점자"
              />
            </View>
          )}
          <View style={{ flexDirection: "row", gap: 10 }}>
            <View style={{ flex: 1 }}>
              <AppButton label="촉각으로 확인" variant="secondary" onPress={() => announce("점자 장치 연결 후에 쓸 수 있어요.")} />
            </View>
            <View style={{ flex: 1 }}>
              <AppButton label="따라 하기" variant="secondary" onPress={() => announce("따라 하기는 준비 중이에요.")} />
            </View>
          </View>
        </>
      )}
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label={index + 1 >= session.problems.length ? "결과 보기" : "다음 문항"}
          onPress={goNext}
          accessibilityHint={lastAnswer ? undefined : undefined}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 28 },
  badge: { width: 150, height: 150, borderRadius: 42 },
  compare: { borderWidth: 1, padding: 18, flexDirection: "row", justifyContent: "space-around" },
});
