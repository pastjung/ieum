import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { authService } from "@/services/authService";
import { announce, hapticError, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

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
      await speakAsync("기기에 저장된 로그인 정보를 지우지 못했어요. 로그인 화면으로 갈게요.");
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
