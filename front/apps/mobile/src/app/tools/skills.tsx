import { useEffect } from "react";
import { Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import { announce } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";
import type { ProficiencyLevel } from "@ieum/contracts";

const levelLabel: Record<ProficiencyLevel, string> = {
  familiar: "익숙해요",
  learning: "배우는 중",
  "needs-practice": "더 연습해요",
};

/** V26 내 실력 · 현재 숙련 */
export default function Skills() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { data: skills } = useQuery({ queryKey: ["skills"], queryFn: learningService.getSkillLevels });

  useEffect(() => {
    if (!skills) return;
    const spoken = skills.map((s) => `${s.label}, ${levelLabel[s.level]}`).join(". ");
    announce(`내 실력이에요. 3단계 기준, 지금 실력이에요. ${spoken}`);
  }, [skills]);

  return (
    <ScreenShell>
      <BackHeader title="내 실력" />
      <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted }}>3단계 기준, 지금 실력이에요.</Text>
      {(skills ?? []).map((skill) => (
        <ListRow
          key={skill.label}
          label={skill.label}
          value={levelLabel[skill.level]}
          emphasizeValue={skill.level === "needs-practice"}
          highlighted={skill.level === "needs-practice"}
        />
      ))}
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label="약한 것부터 복습"
          onPress={() => router.push("/session/intro")}
          accessibilityHint="더 연습이 필요한 것부터 복습을 시작해요"
        />
      </View>
    </ScreenShell>
  );
}
