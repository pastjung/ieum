import { useEffect } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useQuery } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { ListRow } from "@/components/ListRow";
import { AppButton } from "@/components/AppButton";
import { IeumFace } from "@/components/IeumFace";
import { learningService } from "@/services/learningService";
import { todayReview } from "@/services/mockData";
import { useSettingsStore } from "@/stores/settingsStore";
import { announce, hapticTap } from "@/a11y";
import { fontSize, radius, useFontScale, useTheme } from "@/theme";

/** V10·V11 홈 (음성 중심) — 큰 버튼 중심 */
function VoiceHome() {
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
        theme.shadow != null && {
          boxShadow: `0 4px 0 ${primary ? theme.shadow.primary : theme.shadow.control}`,
        },
        pressed && { opacity: 0.8, boxShadow: "none" },
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
            {bigButton("수업 참여", null, false, () => router.push("/(tabs)/class"))}
            <ListRow
              label="점자 장치"
              value={data.deviceConnected ? "연결됨" : "연결 안 됨"}
              accessibilityHint="장치 연결 상태를 확인해요"
              onPress={() => router.push("/device-error")}
            />
          </>
        ) : (
          <>
            {bigButton("복습 시작", null, true, goSession, 1.4)}
            {bigButton("새 단계 학습", null, false, () => router.push("/(tabs)/learn"))}
            {bigButton("수업 참여", null, false, () => router.push("/(tabs)/class"))}
          </>
        )}
      </View>
    </ScreenShell>
  );
}

/** L10 홈 (화면 중심) — 정보 밀도 높은 카드형 */
function ScreenHome() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { data } = useQuery({ queryKey: ["homeSummary"], queryFn: learningService.getHomeSummary });

  return (
    <ScreenShell noBottomPadding>
      <ScrollView contentContainerStyle={{ gap: 14, paddingBottom: 14 }} showsVerticalScrollIndicator={false}>
        <View style={styles.greetRow}>
          <View style={{ flex: 1 }}>
            <Text accessibilityRole="header" style={{ fontSize: 24 * fontScale, fontWeight: "900", color: theme.color.text }}>
              안녕하세요, {data?.userName ?? "학습자"}님
            </Text>
            <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, marginTop: 4 }}>
              {data ? `${data.stepLabel}를 배우는 중이에요` : "불러오는 중이에요…"}
            </Text>
          </View>
          <IeumFace size={56} />
        </View>

        {data?.inProgress && (
          <View
            style={[
              styles.card,
              { backgroundColor: theme.color.primary, borderRadius: radius.card },
              theme.shadow != null && { boxShadow: `0 6px 0 ${theme.shadow.primary}` },
            ]}
            accessibilityLabel={`이어서 학습. ${data.inProgress.title}, ${data.inProgress.progressLabel}`}
          >
            <Text style={{ fontSize: 15 * fontScale, fontWeight: "800", color: theme.color.onPrimary, opacity: 0.85 }}>이어서 학습</Text>
            <Text style={{ fontSize: 24 * fontScale, fontWeight: "900", color: theme.color.onPrimary }}>{data.inProgress.title}</Text>
            <Text style={{ fontSize: 15 * fontScale, fontWeight: "600", color: theme.color.onPrimary }}>{data.inProgress.progressLabel}</Text>
            <AppButton label="이어서 풀기" variant="compact" onPress={() => router.push("/session/intro")} />
          </View>
        )}

        <View
          style={[
            styles.card,
            { backgroundColor: theme.color.surface, borderRadius: radius.card },
            theme.shadow != null && { boxShadow: `0 4px 0 ${theme.shadow.control}` },
          ]}
        >
          <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between" }}>
            <Text style={{ fontSize: 17 * fontScale, fontWeight: "800", color: theme.color.text }}>오늘의 복습</Text>
            <Text
              accessibilityRole="button"
              accessibilityLabel="복습 전체 보기"
              onPress={() => router.push("/tools/skills")}
              style={{ fontSize: 14 * fontScale, fontWeight: "700", color: theme.color.primary }}
            >
              전체 보기 ▸
            </Text>
          </View>
          <View style={{ flexDirection: "row", gap: 8, flexWrap: "wrap" }}>
            {todayReview.map((item) => (
              <Text
                key={item}
                style={{
                  borderWidth: 1.5,
                  borderColor: theme.color.border,
                  borderRadius: 99,
                  paddingHorizontal: 12,
                  paddingVertical: 6,
                  fontSize: 14 * fontScale,
                  fontWeight: "700",
                  color: theme.color.text,
                  overflow: "hidden",
                }}
              >
                {item}
              </Text>
            ))}
          </View>
          <AppButton label="복습 시작" variant="compact" onPress={() => router.push("/session/intro")} />
        </View>

        <ListRow
          label="수업 참여"
          description="참여 코드로 실시간 수업 입장"
          chevron
          onPress={() => router.push("/(tabs)/class")}
        />
        <ListRow
          label="점자 장치"
          value={data?.deviceConnected ? "연결됨" : "연결 안 됨"}
          accessibilityHint="장치 연결 상태를 확인해요"
          onPress={() => router.push("/device-error")}
        />
      </ScrollView>
    </ScreenShell>
  );
}

export default function Home() {
  const mode = useSettingsStore((s) => s.mode);
  return mode === "screen" ? <ScreenHome /> : <VoiceHome />;
}

const styles = StyleSheet.create({
  big: { alignItems: "center", justifyContent: "center", gap: 8 },
  greetRow: { flexDirection: "row", alignItems: "center", gap: 12 },
  card: { padding: 18, gap: 10 },
});
