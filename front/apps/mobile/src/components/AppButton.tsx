import { Pressable, StyleSheet, Text } from "react-native";
import { controlHeight, fontSize, radius, useFontScale, useTheme } from "@/theme";
import { hapticTap } from "@/a11y";

type Variant = "primary" | "secondary" | "compact";

interface Props {
  label: string;
  onPress: () => void;
  variant?: Variant;
  accessibilityHint?: string;
  disabled?: boolean;
  /** 길게 눌러 실행 (제출 등 실수 방지용) */
  requireLongPress?: boolean;
}

/** 와이어프레임 공통 버튼 — primary(노랑 72) / secondary(테두리 60) / compact(56) */
export function AppButton({
  label,
  onPress,
  variant = "primary",
  accessibilityHint,
  disabled,
  requireLongPress,
}: Props) {
  const theme = useTheme();
  const fontScale = useFontScale();
  const isPrimary = variant === "primary";
  const minHeight =
    variant === "primary"
      ? controlHeight.primary
      : variant === "secondary"
        ? controlHeight.secondary
        : controlHeight.compact;
  const size =
    (isPrimary ? fontSize.button : fontSize.buttonSecondary) * fontScale;

  const handle = () => {
    void hapticTap();
    onPress();
  };

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={
        accessibilityHint ??
        (requireLongPress ? "길게 누르면 실행돼요" : undefined)
      }
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={requireLongPress ? undefined : handle}
      onLongPress={requireLongPress ? handle : undefined}
      delayLongPress={requireLongPress ? 500 : undefined}
      style={({ pressed }) => [
        styles.base,
        { minHeight, borderRadius: radius.control },
        isPrimary
          ? { backgroundColor: theme.color.primary }
          : { borderWidth: 2, borderColor: theme.color.border },
        pressed && { opacity: 0.7 },
        disabled && { opacity: 0.4 },
      ]}
    >
      <Text
        style={{
          fontSize: size,
          fontWeight: isPrimary ? "900" : "700",
          color: isPrimary ? theme.color.onPrimary : theme.color.text,
          textAlign: "center",
        }}
      >
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 16,
  },
});
