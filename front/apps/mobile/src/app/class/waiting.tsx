import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { classService } from "@/services/classService";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticSuccess } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** V22 수업 대기 — 선생님이 시작하면 자동 입장 (목: 3초 뒤) */
export default function ClassWaiting() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { title } = useLocalSearchParams<{ title?: string }>();
  const start = useSessionStore((s) => s.start);
  const className = title ?? "수업";

  useEffect(() => {
    void hapticSuccess();
    announce(`입장 완료. ${className}, 곧 시작해요. 선생님이 시작하면 알려 드릴게요.`);
    const t = setTimeout(async () => {
      const session = await classService.getClassSession();
      start(session, "class");
      announce("수업을 시작해요.");
      router.replace("/class/play");
    }, 3000);
    return () => clearTimeout(t);
  }, [className, router, start]);

  return (
    <ScreenShell>
      <View style={styles.center}>
        <View style={[styles.badge, { backgroundColor: theme.color.primary }]} accessibilityLabel="대기 중 캐릭터" />
        <Text style={{ fontSize: 15, fontWeight: "700", color: theme.color.primary }}>입장 완료</Text>
        <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
          {className}{"\n"}곧 시작해요
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29 }}>
          선생님이 시작하면 알려 드릴게요.
        </Text>
      </View>
      <AppButton label="수업 나가기" onPress={() => router.back()} accessibilityHint="대기를 취소하고 수업 참여 화면으로 돌아가요" />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 18 },
  badge: { width: 130, height: 130, borderRadius: 38 },
});
