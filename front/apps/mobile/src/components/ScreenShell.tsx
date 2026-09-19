import type { PropsWithChildren } from "react";
import { StyleSheet, View, type ViewStyle } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { spacing } from "@/theme";
import { useTheme } from "@/theme";

interface Props extends PropsWithChildren {
  style?: ViewStyle;
  /** 하단 여백 없이 꽉 채우기 (탭바 화면 등) */
  noBottomPadding?: boolean;
}

/** 공통 화면 골격 — Dark 배경 + 좌우 20 패딩 */
export function ScreenShell({ children, style, noBottomPadding }: Props) {
  const theme = useTheme();
  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.color.bg }]}
      edges={["top", "left", "right", ...(noBottomPadding ? [] : (["bottom"] as const))]}
    >
      <View
        style={[
          styles.body,
          { paddingBottom: noBottomPadding ? 0 : spacing.lg },
          style,
        ]}
      >
        {children}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  body: { flex: 1, paddingHorizontal: spacing.lg, gap: spacing.md },
});
