import { useEffect } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { ListRow } from "@/components/ListRow";
import { learningService } from "@/services/learningService";
import { announce, hapticTap } from "@/a11y";
import { fontSize, radius, useFontScale, useTheme } from "@/theme";

/** V10·V11 홈 — 이어하기 우선, 없으면 복습 제안 변형 */
export default function Home() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { data } = useQuery({ queryKey: ["homeSummary"], queryFn: learningService.getHomeSummary });

  useEffect(() => {
    if (!data) return;
    announce(
      data.inProgress
        ? `홈이에요. ${data.userName}님, ${data.stepLabel}를 배우는 중이에요. 이어서 학습을 누르면 ${data.inProgress.title}을 계속해요.`
        : `홈이에요. 진행 중인 학습이 없어요. 복습을 시작하거나 새 단계를 배울 수 있어요.`,
    );
  }, [data]);

  const goSession = () => {
    void hapticTap();
    router.push("/session/intro");
  };

  const bigButton = (label: string, sub: string | null, primary: boolean, onPress: () => void, flex = 1) => (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={sub ? `${label}, ${sub}` : label}
      onPress={onPress}
      style={({ pressed }) => [
        styles.big,
        { flex, borderRadius: radius.card },
        primary
          ? { backgroundColor: theme.color.primary }
          : { borderWidth: 2, borderColor: theme.color.border },
        pressed && { opacity: 0.8 },
      ]}
    >
      <Text style={{ fontSize: 28 * fontScale, fontWeight: "900", color: primary ? theme.color.onPrimary : theme.color.text }}>
        {label}
      </Text>
      {sub != null && (
        <Text style={{ fontSize: 16 * fontScale, fontWeight: "600", color: primary ? theme.color.onPrimary : theme.color.textMuted }}>
          {sub}
        </Text>
      )}
    </Pressable>
  );

  return (
    <ScreenShell noBottomPadding>
      <View>
        <Text accessibilityRole="header" style={{ fontSize: fontSize.title * fontScale, fontWeight: "900", color: theme.color.text }}>
          홈
        </Text>
        <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, marginTop: 4 }}>
          {data
            ? data.inProgress
              ? `${data.userName}님, ${data.stepLabel}를 배우는 중이에요.`
              : "진행 중인 학습이 없어요."
            : "불러오는 중이에요…"}
        </Text>
      </View>
      <View style={{ flex: 1, gap: 14, paddingBottom: 14 }}>
        {data?.inProgress ? (
          <>
            {bigButton("이어서 학습", `${data.inProgress.title} · ${data.inProgress.progressLabel}`, true, goSession, 1.4)}
            {bigButton("수업 참여", null, false, () => announce("수업 참여는 준비 중이에요. 다음 업데이트에서 만나요."))}
            <ListRow
              label="점자 장치"
              value={data.deviceConnected ? "연결됨" : "연결 안 됨"}
              accessibilityHint="점자 장치 연결은 준비 중이에요"
              onPress={() => announce("점자 장치 연결은 준비 중이에요.")}
            />
          </>
        ) : (
          <>
            {bigButton("복습 시작", null, true, goSession, 1.4)}
            {bigButton("새 단계 학습", null, false, () => router.push("/(tabs)/learn"))}
            {bigButton("수업 참여", null, false, () => announce("수업 참여는 준비 중이에요."))}
          </>
        )}
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  big: { alignItems: "center", justifyContent: "center", gap: 8 },
});
