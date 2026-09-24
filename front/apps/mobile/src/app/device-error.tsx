import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import type { Dot } from "@ieum/domain";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { tactileDevice } from "@/services/tactileDevice";
import { useTactileStore } from "@/stores/tactileStore";
import { useFontScale, useTheme } from "@/theme";

/** 한글 점자 초성 ㄱ(⠈) — 4점 하나. 1셀 모듈이 살아 있는지 보는 가장 단순한 검사다. */
const GIYEOK_INITIAL: readonly Dot[] = [4];
/** 테스트에서 점을 올려 두는 시간. 손끝으로 확인할 만큼만 — 솔레노이드를 오래 켜 두지 않는다. */
const TEST_HOLD_MS = 2_000;

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

  /** 명령 하나를 보내고 모듈의 응답을 화면에 남긴다. 연결이 없으면 모듈이 던지는 안내를 그대로 전한다. */
  const send = async (label: string, run: () => Promise<string>) => {
    setBusy(true);
    try {
      const reply = await run();
      setDetail(reply.length > 0 ? `${label} 모듈 응답: ${reply}` : `${label} (모듈 응답 없음)`);
      void hapticSuccess();
      announce(label);
    } catch (error) {
      const message = error instanceof Error ? error.message : "모듈에 보내지 못했어요.";
      setDetail(message);
      void hapticError();
      announce(message);
    } finally {
      setBusy(false);
    }
  };

  // 전부 내린 뒤 ㄱ만 올리고, TEST_HOLD_MS 뒤에 다시 내린다 — 이전 패턴이 남아 있어도 ㄱ 하나만 보이게.
  const showGiyeok = () =>
    send("ㄱ, 4번 점을 2초 올렸다 내렸어요.", async () => {
      await tactileDevice.send({ kind: "lowerAll" });
      const raised = await tactileDevice.send({ kind: "raise", dots: GIYEOK_INITIAL });
      await new Promise((resolve) => setTimeout(resolve, TEST_HOLD_MS));
      await tactileDevice.send({ kind: "lowerAll" });
      return raised;
    });

  const lowerAll = () => send("점을 모두 내렸어요.", () => tactileDevice.send({ kind: "lowerAll" }));

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
          label={busy ? "처리 중이에요" : connected ? "다시 연결하기" : "다시 연결 시도"}
          variant="secondary"
          disabled={busy}
          accessibilityHint="블루투스로 짝지어 둔 점자 장치에 연결해요"
          onPress={() => void retry()}
        />
        <AppButton
          label="ㄱ 점자 테스트"
          variant="secondary"
          disabled={busy || !connected}
          accessibilityHint="모듈에 초성 ㄱ, 4번 점 하나를 올려요"
          onPress={() => void showGiyeok()}
        />
        <AppButton
          label="점 모두 내리기"
          variant="secondary"
          disabled={busy || !connected}
          accessibilityHint="올라온 점을 전부 내려요. 솔레노이드가 뜨거워지지 않게 테스트 뒤에 눌러 주세요"
          onPress={() => void lowerAll()}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
