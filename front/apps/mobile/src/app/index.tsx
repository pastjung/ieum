import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { useSettingsStore } from "@/stores/settingsStore";
import { authService } from "@/services/authService";
import { speakAsync } from "@/a11y";
import { useTheme } from "@/theme";

/** V00 스플래시 — 잠시 보여 준 뒤 상태에 따라 분기 */
export default function Splash() {
  const router = useRouter();
  const theme = useTheme();
  const mode = useSettingsStore((s) => s.mode);

  useEffect(() => {
    let cancelled = false;
    const minWait = new Promise((r) => setTimeout(r, 1500));
    // 말이 끝나기 전에 화면이 넘어가지 않도록 낭독 완료를 기다린다
    void Promise.all([speakAsync("점자를 잇다, 이음"), minWait]).then(async () => {
      if (cancelled) return;
      if (!mode) {
        router.replace("/onboarding/mode");
        return;
      }
      const token = await authService.getToken();
      if (cancelled) return;
      router.replace(token ? "/(tabs)" : "/auth/sign-in");
    });
    return () => {
      cancelled = true;
    };
  }, [mode, router]);

  return (
    <ScreenShell style={styles.center}>
      <IeumFace size={110} accessibilityLabel="이음 로고" />
      <View style={styles.wordmark} accessibilityRole="header" accessibilityLabel="이음">
        <Text style={[styles.title, { color: theme.color.text }]}>I</Text>
        <View style={styles.dots}>
          <View style={[styles.dot, { backgroundColor: theme.color.primary }]} />
          <View style={[styles.dot, { backgroundColor: theme.color.primary }]} />
        </View>
        <Text style={[styles.title, { color: theme.color.text }]}>EUM</Text>
      </View>
      <Text style={{ fontSize: 18, color: theme.color.textMuted }}>점자를 잇다, 이음</Text>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 22 },
  title: { fontSize: 44, fontWeight: "900", letterSpacing: -0.5 },
  wordmark: { flexDirection: "row", alignItems: "center", gap: 8 },
  dots: { gap: 8 },
  dot: { width: 10, height: 10, borderRadius: 5 },
});
