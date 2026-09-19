import { useEffect } from "react";
import { Text, View } from "react-native";
import { ScreenShell } from "@/components/ScreenShell";
import { announce } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** 수업 탭 — 실시간 수업(LIVE)은 다음 차수 (V20~V24) */
export default function ClassTab() {
  const theme = useTheme();
  const fontScale = useFontScale();

  useEffect(() => {
    announce("수업 탭이에요. 실시간 수업 참여는 준비 중이에요.");
  }, []);

  return (
    <ScreenShell noBottomPadding>
      <Text accessibilityRole="header" style={{ fontSize: 26 * fontScale, fontWeight: "900", color: theme.color.text }}>
        수업
      </Text>
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 12 }}>
        <Text style={{ fontSize: 22 * fontScale, fontWeight: "800", color: theme.color.text, textAlign: "center" }}>
          실시간 수업은{"\n"}준비 중이에요
        </Text>
        <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, textAlign: "center" }}>
          선생님과 함께하는 수업 참여 기능이 곧 열려요.
        </Text>
      </View>
    </ScreenShell>
  );
}
