import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V29 수업 연결 끊김 */
export default function ClassDisconnected() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { lectureId, title } = useLocalSearchParams<{ lectureId: string; title?: string }>();
  // 다시 연결할 방을 모르면 "다시 연결"을 내놓지 않는다 — undefined로 입장하게 된다
  const canReconnect = typeof lectureId === "string" && lectureId.trim() !== "";
  // 자동으로 다시 잇지 않고 답도 서버에 저장하지 않으므로 그렇게 약속하지 않는다 — 할 일만 안내한다
  const guidance = canReconnect
    ? "다시 연결을 눌러 주세요."
    : "다시 연결할 수업 정보가 없어요. 수업 나가기를 눌러 주세요.";

  useEffect(() => {
    void hapticError();
    announce(`음성 연결이 끊겼어요. ${guidance}`);
  }, [guidance]);

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} expression="worried" accessibilityLabel="연결 끊김 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          음성 연결이{"\n"}끊겼어요
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          {guidance}
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        {canReconnect && (
          <AppButton
            label="다시 연결"
            onPress={() => router.replace({ pathname: "/class/waiting", params: { lectureId, title } })}
          />
        )}
        <AppButton
          label="수업 나가기"
          variant={canReconnect ? "secondary" : "primary"}
          onPress={() =>
            void (async () => {
              await liveLecture.leave();
              lectureProblemSync.reset();
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
