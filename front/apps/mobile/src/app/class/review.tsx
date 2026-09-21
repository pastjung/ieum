import { useEffect, useState } from "react";
import { View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { classService } from "@/services/classService";
import { announce, hapticSelect } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";
import { Text } from "react-native";

/** V24 수업 오답 복습 · 단계 선택 */
export default function ClassReview() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const options = classService.reviewOptions;
  const [selected, setSelected] = useState(options[0]?.id ?? "");
  const chosen = options.find((o) => o.id === selected);

  useEffect(() => {
    announce("수업 오답 복습이에요. 복습할 단계를 골라요.");
  }, []);

  return (
    <ScreenShell>
      <BackHeader title="수업 오답 복습" />
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>복습할 단계를 골라요.</Text>
      {options.map((o) => (
        <ListRow
          key={o.id}
          label={o.label}
          value={`오답 ${o.wrongCount}개`}
          highlighted={o.id === selected}
          accessibilityHint={o.id === selected ? "지금 선택돼 있어요" : "누르면 이 단계를 선택해요"}
          onPress={() => {
            setSelected(o.id);
            void hapticSelect();
            announce(`${o.label} 선택`);
          }}
        />
      ))}
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label={chosen ? `${chosen.label.split(" · ")[0]}로 복습 시작` : "복습 시작"}
          onPress={() => router.replace("/session/intro")}
          accessibilityHint="선택한 단계의 오답으로 복습을 시작해요"
        />
      </View>
    </ScreenShell>
  );
}
