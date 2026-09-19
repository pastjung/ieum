import { useEffect } from "react";
import { View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import {
  fontScaleLabel,
  speechRateLabel,
  useSettingsStore,
  type FontScale,
  type SpeechRate,
} from "@/stores/settingsStore";
import { announce, speak } from "@/a11y";

const rateOrder: SpeechRate[] = ["slow", "normal", "fast"];
const scaleOrder: FontScale[] = ["normal", "large"];

/** V05 접근성 설정 (소리와 진동) — 온보딩과 설정 양쪽에서 진입 */
export default function AccessibilitySettings() {
  const router = useRouter();
  const { from } = useLocalSearchParams<{ from?: string }>();
  const s = useSettingsStore();
  const isScreenMode = s.mode === "screen";

  useEffect(() => {
    announce("소리와 진동 설정이에요. 음성 안내, 말 빠르기, 진동, 글자 크기를 바꿀 수 있어요.");
  }, []);

  const cycleRate = () => {
    const next = rateOrder[(rateOrder.indexOf(s.speechRate) + 1) % rateOrder.length]!;
    s.setSpeechRate(next);
    speak(`말 빠르기 ${speechRateLabel[next]}`, { force: true });
  };

  const cycleScale = () => {
    const next = scaleOrder[(scaleOrder.indexOf(s.fontScale) + 1) % scaleOrder.length]!;
    s.setFontScale(next);
    announce(`글자 크기 ${fontScaleLabel[next]}`);
  };

  return (
    <ScreenShell>
      <BackHeader title="소리와 진동" />
      <ListRow
        label="음성 안내"
        description="화면 내용을 소리로 읽어 줘요"
        value={s.voiceEnabled ? "켬" : "끔"}
        emphasizeValue={s.voiceEnabled}
        accessibilityHint="누르면 음성 안내를 켜거나 꺼요"
        onPress={() => {
          const next = !s.voiceEnabled;
          s.setVoiceEnabled(next);
          speak(next ? "음성 안내를 켰어요" : "음성 안내를 껐어요", { force: true });
        }}
      />
      <ListRow label="말 빠르기" value={`${speechRateLabel[s.speechRate]} ▸`} accessibilityHint="누르면 빠르기가 바뀌어요" onPress={cycleRate} />
      <ListRow
        label="진동 피드백"
        description="정답과 오답을 진동으로 알려 줘요"
        value={s.hapticsEnabled ? "켬" : "끔"}
        emphasizeValue={s.hapticsEnabled}
        accessibilityHint="누르면 진동을 켜거나 꺼요"
        onPress={() => {
          const next = !s.hapticsEnabled;
          s.setHapticsEnabled(next);
          announce(next ? "진동을 켰어요" : "진동을 껐어요");
        }}
      />
      <ListRow label="글자 크기" value={`${fontScaleLabel[s.fontScale]} ▸`} accessibilityHint="누르면 글자 크기가 바뀌어요" onPress={cycleScale} />
      {isScreenMode && (
        <ListRow
          label="고대비 화면"
          description="어두운 배경에 밝은 글자로 보여 줘요"
          value={s.theme === "dark" ? "켬" : "끔"}
          emphasizeValue={s.theme === "dark"}
          accessibilityHint="누르면 고대비 다크 테마로 바뀌어요"
          onPress={() => {
            const next = s.theme === "dark" ? "green" : "dark";
            s.setTheme(next);
            announce(next === "dark" ? "고대비 화면을 켰어요" : "고대비 화면을 껐어요");
          }}
        />
      )}
      <View style={{ marginTop: "auto", gap: 12 }}>
        <AppButton
          label={from === "settings" ? "저장" : "저장하고 계속"}
          onPress={() => {
            if (from === "settings") {
              router.back();
            } else {
              router.replace("/onboarding/guide");
            }
          }}
        />
      </View>
    </ScreenShell>
  );
}
