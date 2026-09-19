import { useEffect } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { useSettingsStore, type UsageMode } from "@/stores/settingsStore";
import { announce, hapticSelect } from "@/a11y";
import { fontSize, radius, useTheme } from "@/theme";

/** V00a 사용 방식 선택 (최초 1회) */
export default function ModeSelect() {
  const router = useRouter();
  const theme = useTheme();
  const setMode = useSettingsStore((s) => s.setMode);

  useEffect(() => {
    announce("어떤 방식이 편하신가요? 음성 중심과 화면 중심 중에 고를 수 있어요. 언제든 설정에서 바꿀 수 있어요.");
  }, []);

  const choose = (mode: UsageMode) => {
    void hapticSelect();
    setMode(mode);
    announce(mode === "voice" ? "음성 중심으로 시작해요" : "화면 중심으로 시작해요");
    router.replace("/auth/sign-up");
  };

  return (
    <ScreenShell>
      <Text accessibilityRole="header" style={{ fontSize: 30, fontWeight: "900", lineHeight: 40, color: theme.color.text }}>
        어떤 방식이{"\n"}편하신가요?
      </Text>
      <Text style={{ fontSize: fontSize.body, color: theme.color.textMuted, lineHeight: 26 }}>
        언제든 설정에서 바꿀 수 있어요.
      </Text>
      <View style={styles.options}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="음성 중심"
          accessibilityHint="음성 안내를 중심으로 사용해요"
          onPress={() => choose("voice")}
          style={({ pressed }) => [
            styles.option,
            { backgroundColor: theme.color.primary, borderRadius: radius.card },
            pressed && { opacity: 0.8 },
          ]}
        >
          <Text style={{ fontSize: 26, fontWeight: "900", color: theme.color.onPrimary }}>음성 중심</Text>
        </Pressable>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="화면 중심"
          accessibilityHint="화면과 터치를 중심으로 사용해요"
          onPress={() => choose("screen")}
          style={({ pressed }) => [
            styles.option,
            { borderWidth: 2, borderColor: theme.color.border, borderRadius: radius.card },
            pressed && { opacity: 0.8 },
          ]}
        >
          <Text style={{ fontSize: 26, fontWeight: "900", color: theme.color.text }}>화면 중심</Text>
        </Pressable>
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  options: { flex: 1, gap: 14 },
  option: { flex: 1, alignItems: "center", justifyContent: "center" },
});
