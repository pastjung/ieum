import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { fontSize, useFontScale, useTheme } from "@/theme";

/** 뒤로가기 + 화면 제목 헤더 */
export function BackHeader({ title }: { title: string }) {
  const theme = useTheme();
  const router = useRouter();
  const fontScale = useFontScale();
  return (
    <View style={styles.row}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="뒤로 가기"
        onPress={() => router.back()}
        style={({ pressed }) => [
          styles.back,
          { borderColor: theme.color.border },
          pressed && { opacity: 0.7 },
        ]}
        hitSlop={10}
      >
        <Text style={{ color: theme.color.text, fontSize: 22, fontWeight: "700" }}>‹</Text>
      </Pressable>
      <Text
        accessibilityRole="header"
        style={{
          color: theme.color.text,
          fontSize: fontSize.heading * fontScale,
          fontWeight: "800",
        }}
      >
        {title}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12 },
  back: {
    width: 44,
    height: 44,
    borderWidth: 2,
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
  },
});
