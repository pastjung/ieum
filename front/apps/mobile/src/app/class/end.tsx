import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { tallyResults } from "@/services/lectureProblemFlow";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { announce, hapticSuccess } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V23c 수업 종료 */
export default function ClassEnd() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  // 이번 수업에서 이 기기로 낸 답의 서버 채점 결과 (앱을 다시 켜면 비어 있다 — 학습자용 수업 결과 API는 없다)
  const results = useLectureProblemStore((s) => s.results);
  const { total, correct } = tallyResults(results);
  const hasWrong = total - correct > 0;
  const summary = total === 0 ? "" : ` ${total}문제 중 ${correct}문제를 맞혔어요.`;

  useEffect(() => {
    void hapticSuccess();
    announce(`수업이 끝났어요. 고생했어요.${summary}` + (hasWrong ? " 틀린 문제로 복습해 볼까요?" : ""));
  }, [summary, hasWrong]);

  const leaveTo = (path: "/class/review" | "/(tabs)") =>
    void (async () => {
      await liveLecture.leave();
      lectureProblemSync.reset();
      router.replace(path);
    })();

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} accessibilityLabel="수업 종료 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 32 * fontScale, fontWeight: "900", color: theme.color.text }}>
          고생했어요
        </Text>
        {total > 0 && (
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
            {total}문제 중 {correct}문제를 맞혔어요.{hasWrong ? " 틀린 문제로 복습해 볼까요?" : ""}
          </Text>
        )}
      </View>
      <View style={{ gap: 12 }}>
        {hasWrong && <AppButton label="오답 복습하기" onPress={() => leaveTo("/class/review")} />}
        <AppButton label="홈으로" variant={hasWrong ? "secondary" : "primary"} onPress={() => leaveTo("/(tabs)")} />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
