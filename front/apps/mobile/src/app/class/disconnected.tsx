import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V29 수업 연결 끊김 */
export default function ClassDisconnected() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { lectureId, title } = useLocalSearchParams<{ lectureId: string; title?: string }>();
  const reset = useSessionStore((s) => s.reset);

  useEffect(() => {
    void hapticError();
    announce("수업과 연결이 잠시 끊겼어요. 다시 잇는 중이에요. 제출한 답은 안전하게 저장돼 있어요.");
  }, []);

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} expression="worried" accessibilityLabel="연결 끊김 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          수업과 연결이{"\n"}잠시 끊겼어요
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          다시 잇는 중이에요.{"\n"}제출한 답은 안전하게 저장돼 있어요.
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        <AppButton
          label="지금 다시 연결"
          onPress={() => router.replace({ pathname: "/class/waiting", params: { lectureId, title } })}
        />
        <AppButton
          label="수업 나가기"
          variant="secondary"
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
