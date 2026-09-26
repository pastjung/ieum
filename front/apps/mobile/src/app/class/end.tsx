import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticSuccess } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V23c 수업 종료 */
export default function ClassEnd() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const correct = useSessionStore((s) => s.correctCount)();
  const total = session?.problems.length ?? 0;
  const reset = useSessionStore((s) => s.reset);
  const hasWrong = total - correct > 0;

  useEffect(() => {
    void hapticSuccess();
    announce(
      `수업이 끝났어요. 고생했어요. ${total}문제 중 ${correct}문제를 맞혔어요.` +
        (hasWrong ? " 틀린 문제로 복습해 볼까요?" : ""),
    );
  }, [correct, total, hasWrong]);

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} accessibilityLabel="수업 종료 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 32 * fontScale, fontWeight: "900", color: theme.color.text }}>
          고생했어요
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          {total}문제 중 {correct}문제를 맞혔어요.{hasWrong ? " 틀린 문제로 복습해 볼까요?" : ""}
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        {hasWrong && (
          <AppButton
            label="오답 복습하기"
            onPress={() =>
              void (async () => {
                await liveLecture.leave();
                reset();
                router.replace("/class/review");
              })()
            }
          />
        )}
        <AppButton
          label="홈으로"
          variant={hasWrong ? "secondary" : "primary"}
          onPress={() =>
            void (async () => {
              await liveLecture.leave();
              reset();
              router.replace("/(tabs)");
            })()
          }
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
