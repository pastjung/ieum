import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { announce, hapticError } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V30 장치(BLE) 오류 — BLE 연동 차수에서 실제 연결 상태와 연결 */
export default function DeviceError() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    void hapticError();
    announce("점자 장치와 연결이 끊겼어요. 장치 없이도 계속 배울 수 있어요.");
  }, []);

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} expression="worried" accessibilityLabel="장치 오류 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          점자 장치와{"\n"}연결이 끊겼어요
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 30 }}>
          장치 없이도 계속 배울 수 있어요.
        </Text>
      </View>
      <View style={{ gap: 12 }}>
        <AppButton label="장치 없이 계속" onPress={() => router.back()} />
        <AppButton
          label="다시 연결 시도"
          variant="secondary"
          onPress={() => announce("장치를 찾고 있어요. 점자 장치 연동은 준비 중이에요.")}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
