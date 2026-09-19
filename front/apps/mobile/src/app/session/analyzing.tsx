import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { announce } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V17 분석 중 */
export default function Analyzing() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    announce("학습 완료! 결과를 분석 중이에요. 잠시만 기다려 주세요.");
    const t = setTimeout(() => router.replace("/session/summary"), 1800);
    return () => clearTimeout(t);
  }, [router]);

  return (
    <ScreenShell style={styles.center}>
      <IeumFace size={130} expression="worried" accessibilityLabel="분석 중 캐릭터" />
      <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
        학습 완료!{"\n"}결과를 분석 중이에요
      </Text>
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, lineHeight: 27, textAlign: "center" }}>
        앱을 닫아도 괜찮아요.{"\n"}다시 열면 이어서 보여 드려요.
      </Text>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 22 },
});
