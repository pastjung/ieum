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
  /** requireLongPress일 때 짧게 터치하면 실행할 동작 (입력 내용 읽어 주기 등) */
  onShortPress?: () => void;
}

/** 와이어프레임 공통 버튼 — primary(노랑 72) / secondary(테두리 60) / compact(56) */
export function AppButton({
  label,
  onPress,
  variant = "primary",
  accessibilityHint,
  disabled,
  requireLongPress,
  onShortPress,
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
        (requireLongPress
          ? onShortPress
            ? "짧게 누르면 입력한 점을 읽어 주고, 길게 누르면 제출돼요"
            : "길게 누르면 실행돼요"
          : undefined)
      }
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={requireLongPress ? onShortPress : handle}
      onLongPress={requireLongPress ? handle : undefined}
      delayLongPress={requireLongPress ? 500 : undefined}
      style={({ pressed }) => [
        styles.base,
        { minHeight, borderRadius: radius.control },
        isPrimary
          ? { backgroundColor: theme.color.primary }
          : { borderWidth: 2, borderColor: theme.color.border, backgroundColor: theme.color.bg },
        theme.shadow != null && {
          boxShadow: `0 4px 0 ${isPrimary ? theme.shadow.primary : theme.shadow.control}`,
        },
        pressed && { opacity: 0.7, boxShadow: "none" },
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
