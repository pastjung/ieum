import { StyleSheet, Text, View } from "react-native";
import type { Level } from "@ieum/contracts";
import { LEVEL_LABEL, LEVEL_UNSET_LABEL } from "@/services/levelLabels";
import { useFontScale, useTheme } from "@/theme";

interface Props {
  /** 아직 고르지 않았으면 null */
  level: Level | null | undefined;
}

/**
 * 홈 우측 상단의 학습 단계 배지.
 *
 * <p>두 홈 변형(`VoiceHome`·`ScreenHome`)이 같은 것을 쓴다 — 화면 모드에 따라 보이는 정보가
 * 달라지면 안 된다. 이 브랜치에서 복습 버튼이 정확히 그렇게 한쪽에만 있었다.
 *
 * 읽기 전용이다. 단계를 바꾸는 곳은 설정 화면 하나뿐이라, 여기서도 바꿀 수 있게 하면
 * 같은 일을 하는 자리가 둘이 된다.
 */
export function LevelBadge({ level }: Props) {
  const theme = useTheme();
  const fontScale = useFontScale();
  const label = level ? LEVEL_LABEL[level] : LEVEL_UNSET_LABEL;

  return (
    <View
      accessible
      accessibilityLabel={`학습 단계, ${label}`}
      style={[
        styles.badge,
        level
          ? { backgroundColor: theme.color.primary }
          : { borderWidth: 1.5, borderColor: theme.color.border },
      ]}
    >
      <Text
        numberOfLines={1}
        style={{
          fontSize: 13 * fontScale,
          fontWeight: "800",
          color: level ? theme.color.onPrimary : theme.color.textMuted,
        }}
      >
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: { paddingHorizontal: 12, paddingVertical: 5, borderRadius: 999, alignSelf: "flex-start" },
});
