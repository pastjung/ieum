import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { fontSize, useFontScale, useTheme } from "@/theme";

/**
 * 뒤로가기 + 화면 제목 헤더.
 *
 * `onBack`을 주면 화면을 떠나는 대신 그 동작을 한다. 여러 단계를 한 화면에서 처리하는
 * 흐름(회원가입)에서 필요하다 — 기본 동작은 화면을 통째로 나가므로, 2단계에서 뒤로를
 * 누른 사용자가 1단계로 가는 대신 입력을 전부 잃는다.
 */
export function BackHeader({ title, onBack }: { title: string; onBack?: () => void }) {
  const theme = useTheme();
  const router = useRouter();
  const fontScale = useFontScale();
  return (
    <View style={styles.row}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="뒤로 가기"
        onPress={onBack ?? (() => router.back())}
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
