import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import { beginLearningSession, learningSessionQueryKey } from "@/services/learningSessionCache";
import { buildSessionStartFailure } from "@/services/learningSessionStartFailure";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticError } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";
import type { LearningThemeId } from "@/services/mockData";

const rules = [
  ["입력", "점을 동시에 짚고 손을 떼면 한 칸"],
  ["삭제", "지우기 버튼으로 마지막 칸 삭제"],
  ["입력 완료", "제출 버튼을 길게 누르기"],
  ["입력 확인", "제출 버튼을 짧게 누르면 입력한 점을 읽어 줘요"],
  ["다시 듣기", "문제를 한 번 더 읽어 줘요"],
] as const;

/** V13 세션 안내 */
export default function SessionIntro() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const queryClient = useQueryClient();
  const { theme: themeId } = useLocalSearchParams<{ theme?: LearningThemeId }>();
  const start = useSessionStore((s) => s.start);
  const { data, isError, errorUpdateCount, refetch } = useQuery({
    queryKey: learningSessionQueryKey(themeId),
    queryFn: () => learningService.startSession(themeId),
    // 세션 생성은 서버에 행을 만든다. 화면에 다시 들어올 때마다 새 세션이 생기면 안 된다
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  });
  const session = data?.session;

  // `session`만 의존성에 두면 실패 안내가 나가지 않는다 — 로딩 중에도 실패 후에도 `session`은
  // undefined라 값이 바뀌지 않아 effect가 다시 돌지 않는다. `isError`만 더해도 부족하다:
  // 재시도가 또 실패하면 `isError`는 계속 true라 값이 그대로다. 실패할 때마다 증가하는
  // `errorUpdateCount`를 함께 본다.
  useEffect(() => {
    if (isError) {
      void hapticError();
      // 시도 횟수를 문구에 담아 낭독 중복 제거(1.5초)에 걸리지 않게 한다
      announce(buildSessionStartFailure(errorUpdateCount).speech);
      return;
    }
    if (!session) return;
    announce(
      `${session.title}. ${session.problems.length}문항이에요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요. 시작하기를 누르면 시작해요.`,
    );
  }, [session, isError, errorUpdateCount]);

  // V32 세션 준비 실패
  if (isError) {
    // 화면 글과 낭독 문구는 같은 곳에서 만든다 (`buildSessionStartFailure` 주석)
    const failure = buildSessionStartFailure(errorUpdateCount);
    return (
      <ScreenShell>
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
          <IeumFace size={130} expression="frown" accessibilityLabel="아쉬워하는 이음 캐릭터" />
          <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
            {failure.headline}
          </Text>
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 30, textAlign: "center" }}
          >
            {failure.detail}
          </Text>
        </View>
        <View style={{ gap: 12 }}>
          <AppButton label="다시 시도" onPress={() => void refetch()} />
          <AppButton label="다른 단계 복습" variant="secondary" onPress={() => router.replace("/(tabs)/learn")} />
        </View>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell>
      <BackHeader title={session?.title ?? "학습 준비 중"} />
      <Text style={{ fontSize: 18 * fontScale, color: theme.color.textMuted }}>
        {session ? `${session.problems.length}문항 · 한 문제씩 천천히 풀어요` : "불러오는 중이에요…"}
      </Text>
      <View style={[styles.card, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
        {rules.map(([k, v]) => (
          <Text key={k} style={{ fontSize: 17 * fontScale, lineHeight: 27, color: theme.color.text }}>
            <Text style={{ fontWeight: "800", color: theme.color.primary }}>{k}</Text> — {v}
          </Text>
        ))}
      </View>
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label="시작하기"
          disabled={!session}
          onPress={() => {
            if (!data) return;
            beginLearningSession(queryClient, themeId, data, start);
            router.replace("/session/play");
          }}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, padding: 20, gap: 14 },
});
