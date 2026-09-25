import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { learningService } from "@/services/learningService";
import {
  beginLearningSession,
  learningSessionQueryKey,
  type SessionRequest,
} from "@/services/learningSessionCache";
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
  // `mode=review`로 들어오면 복습 세션을 만든다. 주제(theme)는 무시된다 — 복습은 서버가
  // 내 기록에서 약한 요소를 골라 출제하므로 고를 단계가 없다
  const { theme: themeId, mode, sessionId } = useLocalSearchParams<{
    theme?: LearningThemeId;
    mode?: string;
    sessionId?: string;
  }>();
  const request: SessionRequest =
    mode === "review"
      ? { kind: "review" }
      : mode === "resume" && sessionId
        ? { kind: "resume", sessionId: Number(sessionId) }
        : { kind: "theme", themeId };
  const start = useSessionStore((s) => s.start);
  const { data, isError, error, errorUpdateCount, refetch } = useQuery({
    queryKey: learningSessionQueryKey(request),
    queryFn: () => {
      switch (request.kind) {
        case "review":
          return learningService.startReviewSession();
        // 새 문항을 배정하지 않고 기존 배정을 이어받는다
        case "resume":
          return learningService.resumeSession(request.sessionId);
        default:
          return learningService.startSession(themeId);
      }
    },
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
      // 시도 횟수를 문구에 담아 낭독 중복 제거(1.5초)에 걸리지 않게 한다.
      // `error`를 함께 넘긴다 — 이걸 빠뜨리면 LEARNING_CONTENT_NOT_READY(그 단계에 문제가
      // 없어 재시도로는 절대 풀리지 않는 실패)까지 "잠시 후 다시 해 보세요"가 되어
      // 학습자가 무한히 재시도한다
      announce(buildSessionStartFailure(errorUpdateCount, error).speech);
      return;
    }
    if (!session) return;
    announce(
      `${session.title}. ${session.problems.length}문항이에요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요. 시작하기를 누르면 시작해요.`,
    );
  }, [session, isError, error, errorUpdateCount]);

  // V32 세션 준비 실패
  if (isError) {
    // 화면 글과 낭독 문구는 같은 곳에서 만든다 (`buildSessionStartFailure` 주석)
    const failure = buildSessionStartFailure(errorUpdateCount, error);
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
        {/*
          버튼 순서와 variant를 안내가 권한 행동(`failure.recovery`)에 맞춘다. 맨 위·primary가
          주 버튼이고 TalkBack 포커스도 거기 먼저 닿으므로, 낭독이 "다른 단계를 골라 주세요"
          라고 해 놓고 "다시 시도"를 그 자리에 두면 안내가 하지 말라고 한 행동이 가장 누르기
          쉬운 자리에 놓인다 — 그 경우 재시도는 영구 실패라 무한 재시도가 그대로 남는다.
        */}
        <View style={{ gap: 12 }}>
          {(failure.recovery === "retry"
            ? ([
                ["다시 시도", () => void refetch()],
                ["다른 단계 복습", () => router.replace("/(tabs)/learn")],
              ] as const)
            : ([
                ["다른 단계 복습", () => router.replace("/(tabs)/learn")],
                ["다시 시도", () => void refetch()],
              ] as const)
          ).map(([label, onPress], order) => (
            <AppButton key={label} label={label} variant={order === 0 ? "primary" : "secondary"} onPress={onPress} />
          ))}
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
            beginLearningSession(queryClient, request, data, start);
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
