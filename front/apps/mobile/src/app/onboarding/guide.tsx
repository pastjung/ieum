import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import type { Dot } from "@ieum/domain";
import { cellToDots } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { AppButton } from "@/components/AppButton";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce, hapticSuccess } from "@/a11y";
import { useTheme } from "@/theme";

const guideSteps: { text: string; highlight: Dot[] }[] = [
  { text: "점을 동시에 짚고 손을 떼면 한 칸이 입력돼요. 1번 점을 눌러 보세요.", highlight: [1] },
  { text: "이번엔 1번과 2번 점을 함께 눌러 보세요.", highlight: [1, 2] },
  { text: "잘했어요! 이제 편하게 여러 점을 눌러 보고, 완료를 누르면 시작해요.", highlight: [] },
];

/** V09 사용 안내 · 3단계 (6점 패드 체험) */
export default function Guide() {
  const router = useRouter();
  const theme = useTheme();
  const { from } = useLocalSearchParams<{ from?: string }>();
  const setOnboarded = useSettingsStore((s) => s.setOnboarded);
  const [step, setStep] = useState(0);
  const current = guideSteps[step]!;

  useEffect(() => {
    announce(`사용 안내 ${step + 1}단계. ${current.text}`);
  }, [step, current]);

  const finish = () => {
    setOnboarded(true);
    if (from === "settings") {
      router.back();
    } else {
      router.replace("/(tabs)");
    }
  };

  return (
    <ScreenShell>
      <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
        <Text accessibilityRole="header" style={{ fontSize: 22, fontWeight: "800", color: theme.color.text }}>
          사용 안내 · {step + 1}/{guideSteps.length}
        </Text>
        <AppButton label="건너뛰기" variant="compact" onPress={finish} accessibilityHint="안내를 건너뛰고 시작해요" />
      </View>
      <Text style={{ fontSize: 17, color: theme.color.textMuted, lineHeight: 29 }}>{current.text}</Text>
      <DotPad
        highlightDots={current.highlight}
        onCommitCell={(cell) => {
          const dots = cellToDots(cell);
          void hapticSuccess();
          announce(`${dots.join("번, ")}번 점, 한 칸 입력됐어요.`);
        }}
      />
      <AppButton
        label={step < guideSteps.length - 1 ? "다음" : "완료"}
        onPress={() => (step < guideSteps.length - 1 ? setStep(step + 1) : finish())}
      />
    </ScreenShell>
  );
}
