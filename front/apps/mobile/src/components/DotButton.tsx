import { Pressable, Text } from "react-native";
import type { Dot } from "@ieum/domain";
import { useTheme } from "@/theme";

interface Props {
  dot: Dot;
  size: number;
  on: boolean;
  selected: boolean;
  onPressIn: () => void;
  onPressOut: () => void;
  style?: object;
}

/** 6점 패드의 점 버튼 하나 (92px 이상 계약) */
export function DotButton({ dot, size, on, selected, onPressIn, onPressOut, style }: Props) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${dot}번 점`}
      accessibilityState={{ selected }}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      style={[
        {
          position: "absolute",
          width: size,
          height: size,
          borderRadius: size / 2,
          borderWidth: 3,
          alignItems: "center",
          justifyContent: "center",
          borderColor: on ? theme.color.primary : theme.color.border,
          backgroundColor: on ? theme.color.primary : "transparent",
        },
        style,
      ]}
    >
      <Text style={{ fontSize: size > 100 ? 32 : 26, fontWeight: "900", color: on ? theme.color.onPrimary : theme.color.text }}>
        {dot}
      </Text>
    </Pressable>
  );
}
