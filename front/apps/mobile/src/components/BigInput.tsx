import { StyleSheet, Text, TextInput, View, type TextInputProps } from "react-native";
import { fontSize, radius, useFontScale, useTheme } from "@/theme";

interface Props extends Pick<TextInputProps, "value" | "onChangeText" | "secureTextEntry" | "keyboardType" | "autoCapitalize" | "autoFocus"> {
  label?: string;
  accessibilityLabel: string;
  errorMessage?: string;
  focused?: boolean;
}

/** 와이어프레임 공통 대형 입력창 (높이 64+, 노랑 포커스 테두리) */
export function BigInput({ label, accessibilityLabel, errorMessage, focused, ...input }: Props) {
  const theme = useTheme();
  const fontScale = useFontScale();
  return (
    <View style={styles.wrap}>
      {label != null && (
        <Text style={{ fontSize: 15 * fontScale, fontWeight: "700", color: theme.color.textMuted }}>{label}</Text>
      )}
      <TextInput
        accessibilityLabel={accessibilityLabel}
        accessibilityHint={errorMessage}
        style={[
          styles.input,
          {
            borderColor: focused ? theme.color.primary : theme.color.border,
            color: theme.color.text,
            borderRadius: radius.control,
            fontSize: fontSize.heading * fontScale,
          },
        ]}
        placeholderTextColor={theme.color.textMuted}
        {...input}
      />
      {errorMessage != null && (
        <Text accessibilityLiveRegion="polite" style={{ fontSize: 15 * fontScale, fontWeight: "700", color: theme.color.primary }}>
          {errorMessage}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: 8 },
  input: { borderWidth: 2, minHeight: 64, paddingHorizontal: 18, fontWeight: "700" },
});
