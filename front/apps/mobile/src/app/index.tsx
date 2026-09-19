import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { useSettingsStore } from "@/stores/settingsStore";
import { authService } from "@/services/authService";
import { announce } from "@/a11y";
import { useTheme } from "@/theme";

/** V00 스플래시 — 잠시 보여 준 뒤 상태에 따라 분기 */
export default function Splash() {
  const router = useRouter();
  const theme = useTheme();
  const mode = useSettingsStore((s) => s.mode);

  useEffect(() => {
    announce("점자를 잇다, 이음");
    const t = setTimeout(async () => {
      if (!mode) {
        router.replace("/onboarding/mode");
        return;
      }
      const token = await authService.getToken();
      router.replace(token ? "/(tabs)" : "/auth/sign-in");
    }, 1500);
    return () => clearTimeout(t);
  }, [mode, router]);

  return (
    <ScreenShell style={styles.center}>
      <View style={[styles.logo, { backgroundColor: theme.color.primary }]} accessibilityLabel="이음 로고" />
      <Text style={[styles.title, { color: theme.color.text }]} accessibilityRole="header">
        I:EUM
      </Text>
      <Text style={{ fontSize: 18, color: theme.color.textMuted }}>점자를 잇다, 이음</Text>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 22 },
  logo: { width: 110, height: 110, borderRadius: 32 },
  title: { fontSize: 44, fontWeight: "900", letterSpacing: -0.5 },
});
