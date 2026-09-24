import { useState } from "react";
import { Pressable, StyleSheet, Text, TextInput, View, type TextInputProps } from "react-native";
import { fontSize, radius, useFontScale, useTheme } from "@/theme";

interface Props extends Pick<TextInputProps, "value" | "onChangeText" | "secureTextEntry" | "keyboardType" | "autoCapitalize" | "autoFocus"> {
  label?: string;
  accessibilityLabel: string;
  /** 입력 전에 알아야 하는 규칙. 화면에도 띄우고 스크린리더에도 같은 내용을 준다 */
  hint?: string;
  errorMessage?: string;
  focused?: boolean;
}

/** 와이어프레임 공통 대형 입력창 (높이 64+, 노랑 포커스 테두리, 비밀번호 보기 토글) */
export function BigInput({ label, accessibilityLabel, hint, errorMessage, focused, secureTextEntry, ...input }: Props) {
  const theme = useTheme();
  const fontScale = useFontScale();
  const [hidden, setHidden] = useState(true);
  const secure = !!secureTextEntry;
  return (
    <View style={styles.wrap}>
      {label != null && (
        <Text style={{ fontSize: 15 * fontScale, fontWeight: "700", color: theme.color.textMuted }}>{label}</Text>
      )}
      <View
        style={[
          styles.inputRow,
          {
            borderColor: focused ? theme.color.primary : theme.color.border,
            borderRadius: radius.control,
          },
        ]}
      >
        <TextInput
          accessibilityLabel={accessibilityLabel}
          // 오류가 있으면 그것이 먼저다. 없을 때만 규칙을 읽어 준다 —
          // 화면을 못 보는 사용자도 입력 전에 무엇이 필요한지 알아야 한다
          accessibilityHint={errorMessage ?? hint}
          secureTextEntry={secure && hidden}
          style={[styles.input, { color: theme.color.text, fontSize: fontSize.heading * fontScale }]}
          placeholderTextColor={theme.color.textMuted}
          {...input}
        />
        {secure && (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={hidden ? "비밀번호 보이기" : "비밀번호 숨기기"}
            onPress={() => setHidden((v) => !v)}
            hitSlop={10}
            style={styles.toggle}
          >
            <Text style={{ fontSize: 15, fontWeight: "800", color: theme.color.textMuted }}>
              {hidden ? "보기" : "숨김"}
            </Text>
          </Pressable>
        )}
      </View>
      {errorMessage == null && hint != null && (
        <Text style={{ fontSize: 14 * fontScale, color: theme.color.textMuted, lineHeight: 22 }}>{hint}</Text>
      )}
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
  inputRow: {
    borderWidth: 2,
    minHeight: 64,
    flexDirection: "row",
    alignItems: "center",
  },
  input: { flex: 1, paddingHorizontal: 18, fontWeight: "700", minHeight: 64 },
  toggle: { paddingHorizontal: 16, minHeight: 64, justifyContent: "center" },
});
