import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce, hapticSuccess } from "@/a11y";
import { useTheme } from "@/theme";

/** V04 로그인 완료 */
export default function Welcome() {
  const router = useRouter();
  const theme = useTheme();
  const { userName } = useLocalSearchParams<{ userName?: string }>();
  const onboarded = useSettingsStore((s) => s.onboarded);
  const name = userName ?? "학습자";

  useEffect(() => {
    void hapticSuccess();
    announce(`${name}님, 환영해요.`);
  }, [name]);

  return (
    <ScreenShell>
      <View style={styles.center}>
        <IeumFace size={130} />
        <Text accessibilityRole="header" style={{ fontSize: 30, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          {name}님,{"\n"}환영해요
        </Text>
      </View>
      <AppButton
        label="시작하기"
        onPress={() => router.replace(onboarded ? "/(tabs)" : "/onboarding/accessibility")}
      />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
});
