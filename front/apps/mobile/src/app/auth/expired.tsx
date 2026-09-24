import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { announce, hapticError, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** 낭독이 무응답이어도 화면 전환을 막지 않을 상한 */
const SPEAK_WAIT_MS = 3000;

/**
 * 문장을 끝까지 읽어 준 뒤 화면을 넘기되, **그 기다림에 갇히지 않는다.**
 *
 * 이 화면의 버튼은 시각장애인 사용자에게 유일한 탈출구다. `speakAsync`의 TTS 경로는
 * expo-speech의 onDone·onStopped·onError에만 의존해, 셋 중 무엇도 불리지 않으면 영영
 * settle되지 않고 `Speech.speak`가 던지면 reject된다 — 어느 쪽이든 뒤따르는 화면 전환에
 * 도달하지 못해 버튼이 다시 죽는다. 거부는 흡수하고, 무응답은 상한에서 끊는다.
 */
async function speakBeforeLeaving(text: string): Promise<void> {
  try {
    await Promise.race([speakAsync(text), new Promise<void>((resolve) => setTimeout(resolve, SPEAK_WAIT_MS))]);
  } catch {
    // 낭독이 실패한 것은 화면 전환을 막을 이유가 되지 않는다
  }
}

/** V31 로그인 만료 — 토큰 만료 시 전역에서 이 화면으로 보낸다 (API 연동 차수에서 연결) */
export default function SessionExpired() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    announce("오랜만이에요. 안전을 위해 로그인이 만료됐어요. 학습 기록은 모두 안전해요. 다시 로그인해 주세요.");
  }, []);

  const goSignIn = async () => {
    try {
      await authService.signOut();
    } catch {
      // 이 화면의 유일한 버튼이다. 저장소 정리가 거부돼도 전환을 막으면 빠져나갈 수단이 없다.
      // 실패를 알리되, 다음 화면의 진입 안내에 잘리지 않게 낭독을 기다렸다가 이동한다
      void hapticError();
      await speakBeforeLeaving("기기에 저장된 로그인 정보를 지우지 못했어요. 로그인 화면으로 갈게요.");
    }
    router.replace("/auth/sign-in");
  };

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} expression="frown" accessibilityLabel="로그인 만료 캐릭터" />
        <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          오랜만이에요,{"\n"}다시 로그인해 주세요
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}>
          안전을 위해 로그인이 만료됐어요.{"\n"}학습 기록은 모두 안전해요.
        </Text>
      </View>
      <AppButton label="로그인하러 가기" onPress={() => void goSignIn()} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
