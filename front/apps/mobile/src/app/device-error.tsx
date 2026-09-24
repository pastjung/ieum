import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { tactileDevice } from "@/services/tactileDevice";
import { useTactileStore } from "@/stores/tactileStore";
import { useFontScale, useTheme } from "@/theme";

/** V30 장치 오류 — HC-06(블루투스 Classic) 연결을 다시 시도한다. */
export default function DeviceError() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const connected = useTactileStore((s) => s.connected);
  const name = useTactileStore((s) => s.name);
  const [busy, setBusy] = useState(false);
  const [detail, setDetail] = useState("");

  useEffect(() => {
    if (connected) return;
    void hapticError();
    announce("점자 장치와 연결이 끊겼어요. 장치 없이도 계속 배울 수 있어요.");
  }, [connected]);

  const retry = async () => {
    setBusy(true);
    setDetail("장치를 찾고 있어요.");
    announce("장치를 찾고 있어요.");
    try {
      const found = await tactileDevice.connect();
      setDetail(`${found}에 연결했어요.`);
      void hapticSuccess();
      announce(`${found}에 연결했어요.`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "연결하지 못했어요.";
      setDetail(message);
      void hapticError();
      announce(message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace
          size={130}
          expression={connected ? "happy" : "worried"}
          accessibilityLabel={connected ? "장치 연결됨 캐릭터" : "장치 오류 캐릭터"}
        />
        <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          {connected ? (
            <>점자 장치가{"\n"}연결돼 있어요</>
          ) : (
            <>점자 장치와{"\n"}연결이 끊겼어요</>
          )}
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 30, textAlign: "center" }}>
          {connected ? (name ?? "HC-06") : "장치 없이도 계속 배울 수 있어요."}
        </Text>
        {detail.length > 0 && (
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 15 * fontScale, color: theme.color.text, lineHeight: 24, textAlign: "center" }}
          >
            {detail}
          </Text>
        )}
      </View>
      <View style={{ gap: 12 }}>
        <AppButton label="장치 없이 계속" onPress={() => router.back()} />
        <AppButton
          label={busy ? "연결하는 중이에요" : connected ? "다시 연결하기" : "다시 연결 시도"}
          variant="secondary"
          disabled={busy}
          accessibilityHint="블루투스로 짝지어 둔 점자 장치에 연결해요"
          onPress={() => void retry()}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
