import { Pressable, StyleSheet, Text, View } from "react-native";
import { controlHeight, fontSize, radius, useFontScale, useTheme } from "@/theme";
import { hapticSelect } from "@/a11y";

interface Props {
  label: string;
  value?: string;
  /** value를 강조(노랑 칩)로 표시 */
  emphasizeValue?: boolean;
  onPress?: () => void;
  accessibilityHint?: string;
  chevron?: boolean;
}

/** 설정·목록 공통 행 (최소 높이 64) */
export function ListRow({
  label,
  value,
  emphasizeValue,
  onPress,
  accessibilityHint,
  chevron,
}: Props) {
  const theme = useTheme();
  const fontScale = useFontScale();
  const content = (
    <>
      <Text
        style={{
          color: theme.color.text,
          fontSize: fontSize.buttonSecondary * fontScale,
          fontWeight: "700",
          flexShrink: 1,
        }}
      >
        {label}
      </Text>
      <View style={styles.valueWrap}>
        {value != null &&
          (emphasizeValue ? (
            <Text
              style={[
                styles.chip,
                { backgroundColor: theme.color.primary, color: theme.color.onPrimary },
              ]}
            >
              {value}
            </Text>
          ) : (
            <Text
              style={{
                color: theme.color.primary,
                fontSize: fontSize.body * fontScale,
                fontWeight: "800",
              }}
            >
              {value}
            </Text>
          ))}
        {chevron && (
          <Text style={{ color: theme.color.textMuted, fontSize: 20 }}>▸</Text>
        )}
      </View>
    </>
  );
  const a11yLabel = value != null ? `${label}, ${value}` : label;
  return (
    <Pressable
      accessibilityRole={onPress ? "button" : "text"}
      accessibilityLabel={a11yLabel}
      accessibilityHint={accessibilityHint}
      disabled={!onPress}
      onPress={
        onPress &&
        (() => {
          void hapticSelect();
          onPress();
        })
      }
      style={({ pressed }) => [
        styles.row,
        { borderColor: theme.color.border, minHeight: controlHeight.row, borderRadius: radius.control },
        pressed && { opacity: 0.7 },
      ]}
    >
      {content}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    borderWidth: 2,
    paddingHorizontal: 18,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 12,
  },
  valueWrap: { flexDirection: "row", alignItems: "center", gap: 6 },
  chip: {
    borderRadius: 99,
    paddingHorizontal: 16,
    paddingVertical: 6,
    fontSize: 15,
    fontWeight: "800",
    overflow: "hidden",
  },
});
