import { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { AppButton } from "@/components/AppButton";
import { useSettingsStore, type UsageMode } from "@/stores/settingsStore";
import { announce, hapticSelect } from "@/a11y";
import { fontSize, radius, useTheme } from "@/theme";

const options: { mode: UsageMode; title: string; desc: string }[] = [
  { mode: "voice", title: "음성 중심", desc: "소리와 진동으로 안내해요" },
  { mode: "screen", title: "화면 중심", desc: "보면서 배우는 분께 좋아요" },
];

/** V00a·L00a 사용 방식 선택 (최초 1회) — 고른 뒤 '선택하고 시작'으로 확정 */
export default function ModeSelect() {
  const router = useRouter();
  const theme = useTheme();
  const setMode = useSettingsStore((s) => s.setMode);
  const [selected, setSelected] = useState<UsageMode | null>(null);

  useEffect(() => {
    announce("어떤 방식이 편하신가요? 음성 중심과 화면 중심 중에 고르고, 선택하고 시작을 누르세요. 언제든 설정에서 바꿀 수 있어요.");
  }, []);

  const confirm = () => {
    if (!selected) {
      announce("먼저 음성 중심과 화면 중심 중 하나를 골라 주세요.");
      return;
    }
    setMode(selected);
    announce(selected === "voice" ? "음성 중심으로 시작해요" : "화면 중심으로 시작해요");
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
        {options.map((o) => {
          const isSelected = selected === o.mode;
          return (
            <Pressable
              key={o.mode}
              accessibilityRole="button"
              accessibilityLabel={`${o.title}. ${o.desc}`}
              accessibilityState={{ selected: isSelected }}
              onPress={() => {
                void hapticSelect();
                setSelected(o.mode);
                announce(`${o.title} 선택`);
              }}
              style={({ pressed }) => [
                styles.option,
                { borderRadius: radius.card },
                isSelected
                  ? { backgroundColor: theme.color.primary, borderWidth: 0 }
                  : { borderWidth: 2, borderColor: theme.color.border },
                theme.shadow != null && {
                  boxShadow: `0 6px 0 ${isSelected ? theme.shadow.primary : theme.shadow.control}`,
                },
                pressed && { opacity: 0.8, boxShadow: "none" },
              ]}
            >
              {isSelected && (
                <Text style={{ fontSize: 22, fontWeight: "900", color: theme.color.onPrimary }}>✓</Text>
              )}
              <Text style={{ fontSize: 26, fontWeight: "900", color: isSelected ? theme.color.onPrimary : theme.color.text }}>
                {o.title}
              </Text>
              <Text style={{ fontSize: 15, fontWeight: "600", color: isSelected ? theme.color.onPrimary : theme.color.textMuted }}>
                {o.desc}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <AppButton label="선택하고 시작" onPress={confirm} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  options: { flex: 1, gap: 14 },
  option: { flex: 1, alignItems: "center", justifyContent: "center", gap: 6 },
});
