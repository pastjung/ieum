import { useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { LearningSession } from "@ieum/contracts";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { learningService, type InProgressKind } from "@/services/learningService";
import {
  beginLearningSession,
  inProgressSessionQueryKey,
  learningSessionQueryKey,
  type SessionRequest,
} from "@/services/learningSessionCache";
import { buildSessionStartFailure } from "@/services/learningSessionStartFailure";
import { describeSessionConflict } from "@/services/sessionStartConflict";
import { useSessionStore, type ServerSessionMeta } from "@/stores/sessionStore";
import { announce, hapticError } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

const rules = [
  ["입력", "점을 동시에 짚고 손을 떼면 한 칸"],
  ["삭제", "지우기 버튼으로 마지막 칸 삭제"],
  ["입력 완료", "제출 버튼을 길게 누르기"],
  ["입력 확인", "제출 버튼을 짧게 누르면 입력한 점을 읽어 줘요"],
  ["다시 듣기", "문제를 한 번 더 읽어 줘요"],
] as const;

/**
 * 새 세션에 서버가 배정하는 문항 수. 학습·복습 모두 12문항이다 (`ProblemService`).
 * 세션을 만들기 전에 안내해야 해서 앱이 이 값을 안다.
 */
const NEW_SESSION_PROBLEM_COUNT = 12;

type LoadedSession = { session: LearningSession; meta: ServerSessionMeta };

/** 세션 요청을 서버 호출로 옮긴다. 이어하기만 조회이고, 나머지는 서버에 새 세션을 만든다 */
function requestSession(request: SessionRequest): Promise<LoadedSession> {
  switch (request.kind) {
    case "review":
      return learningService.startReviewSession();
    // 새 문항을 배정하지 않고 기존 배정을 이어받는다
    case "resume":
      return learningService.resumeSession(request.sessionId);
    default:
      return learningService.startSession(request.curriculumId, request.title);
  }
}

/** V13 세션 안내 */
export default function SessionIntro() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const queryClient = useQueryClient();
  // `mode=review`로 들어오면 복습 세션을 만든다. 주제(theme)는 무시된다 — 복습은 서버가
  // 내 기록에서 약한 요소를 골라 출제하므로 고를 단계가 없다
  const { curriculumId, title, mode, sessionId } = useLocalSearchParams<{
    curriculumId?: string;
    title?: string;
    mode?: string;
    sessionId?: string;
  }>();
  const request: SessionRequest =
    mode === "review"
      ? { kind: "review" }
      : mode === "resume" && sessionId
        ? { kind: "resume", sessionId: Number(sessionId) }
        : { kind: "stage", curriculumId: Number(curriculumId), title: title ?? "학습" };
  const start = useSessionStore((s) => s.start);
  const isResume = request.kind === "resume";

  /*
    새 세션을 만들면 같은 종류의 미완료 세션이 조용히 닫힌다
    (`ProblemService.createSession`). 행이 지워지지는 않지만 학습자는 이어서 풀 기회를
    잃고, `/completion`을 거치지 않은 채 "완료"로 기록된다.

    그래서 **세션을 만들기 전에** 묻는다. 진행 중 세션 조회가 끝나기 전에는 시작할 수 없게
    막는다 — 그 전에 만들면 물어볼 기회가 없다.
  */
  // 시작하려는 종류의 진행 중 세션만 본다. 두 종류를 함께 조회하면 더 최근인 다른 종류에 가려져,
  // 새 세션이 닫을 세션이 있는데도 묻지 못한다. 이어하기는 새 세션을 만들지 않아 물을 것이 없다
  const conflictKind: InProgressKind = request.kind === "review" ? "review" : "learning";
  const inProgressQuery = useQuery({
    queryKey: inProgressSessionQueryKey(conflictKind),
    queryFn: () => learningService.getInProgressSession(conflictKind),
    enabled: !isResume,
  });
  const inProgress = inProgressQuery.data;
  // 조회하지 않는 쿼리도 값이 없으면 pending이라, 이어하기에서는 기다리지 않는다
  const inProgressPending = !isResume && inProgressQuery.isPending;
  const [confirmed, setConfirmed] = useState(false);
  const conflict = describeSessionConflict(request, inProgressPending ? undefined : inProgress);
  const awaitingConfirm = conflict !== null && !confirmed;

  /*
    이어하기는 조회만 한다 — 서버에 행을 만들지 않으므로 화면이 열릴 때 불러와 남은 문항
    수를 보여준다.
  */
  const resumeQuery = useQuery({
    queryKey: learningSessionQueryKey(request),
    enabled: isResume && !inProgressPending && !awaitingConfirm,
    queryFn: () => requestSession(request),
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  });

  /*
    새 학습·복습 세션은 "시작하기"를 누를 때 만든다. 예전에는 이 화면이 열리자마자 만들어,
    안내만 보고 나가도 빈 세션이 남아 홈에 "이어서 학습 0/12"로 떴다.
  */
  const createSession = useMutation({ mutationFn: () => requestSession(request) });
  // 실패할 때마다 늘린다. 실패 안내의 시도 횟수이자 낭독 중복 제거를 피하는 값이다
  const [createFailures, setCreateFailures] = useState(0);
  // 같은 프레임의 두 번째 탭은 버튼이 잠기기 전에 들어온다. 그대로 두면 세션이 둘 생긴다
  const creatingRef = useRef(false);

  const creating = createSession.isPending || createSession.isSuccess;
  const session = resumeQuery.data?.session;
  const isError = isResume ? resumeQuery.isError : createSession.isError;
  const error = isResume ? resumeQuery.error : createSession.error;
  const errorCount = isResume ? resumeQuery.errorUpdateCount : createFailures;
  const headerTitle = isResume
    ? (session?.title ?? "학습 준비 중")
    : request.kind === "stage"
      ? request.title
      : "복습";

  const enter = (loaded: LoadedSession) => {
    beginLearningSession(queryClient, request, loaded, start);
    // 이어받았는데 풀 문항이 남지 않았다면 완료 제출만 남은 세션이다
    router.replace(
      (loaded.meta.startIndex ?? 0) >= loaded.session.problems.length
        ? "/session/analyzing"
        : "/session/play",
    );
  };

  const startNew = () => {
    if (creatingRef.current) return;
    creatingRef.current = true;
    createSession.mutate(undefined, {
      onSuccess: enter,
      onError: () => {
        creatingRef.current = false;
        setCreateFailures((n) => n + 1);
      },
    });
  };

  const retry = isResume ? () => void resumeQuery.refetch() : startNew;

  // 이어하기의 남은 문항 수. 새 세션은 만들기 전이라 서버가 배정할 수를 그대로 쓴다
  const remaining = isResume
    ? session
      ? session.problems.length - (resumeQuery.data?.meta.startIndex ?? 0)
      : null
    : NEW_SESSION_PROBLEM_COUNT;

  // 실패 안내는 실패할 때마다 늘어나는 `errorCount`를 함께 본다 — `isError`만 보면 재시도가
  // 또 실패해도 값이 그대로라 effect가 다시 돌지 않는다.
  useEffect(() => {
    // 확인을 기다리는 동안에는 세션 안내를 읽지 않는다 — 아직 만들어지지도 않았다
    if (awaitingConfirm && conflict !== null) {
      announce(conflict.speech);
      return;
    }
    if (isError) {
      void hapticError();
      // 시도 횟수를 문구에 담아 낭독 중복 제거(1.5초)에 걸리지 않게 한다.
      // `error`를 함께 넘긴다 — 이걸 빠뜨리면 LEARNING_CONTENT_NOT_READY(그 단계에 문제가
      // 없어 재시도로는 절대 풀리지 않는 실패)까지 "잠시 후 다시 해 보세요"가 되어
      // 학습자가 무한히 재시도한다
      announce(buildSessionStartFailure(errorCount, error).speech);
      return;
    }
    // 진행 중 세션 조회 전에는 충돌 확인이 뜰 수 있어 안내를 미룬다. 시작을 누른 뒤에는
    // 곧 문항 화면으로 넘어가므로 안내를 다시 읽지 않는다
    if (inProgressPending || remaining === null || creating) return;
    announce(
      remaining <= 0
        ? `${headerTitle}. 문제를 모두 풀었어요. 시작하기를 누르면 결과를 받아요.`
        : `${headerTitle}. ${remaining}문항이에요. 점을 동시에 짚고 손을 떼면 한 칸이 입력돼요. 시작하기를 누르면 시작해요.`,
    );
  }, [headerTitle, remaining, isError, error, errorCount, awaitingConfirm, conflict, inProgressPending, creating]);

  // V32 세션 준비 실패
  if (isError) {
    // 화면 글과 낭독 문구는 같은 곳에서 만든다 (`buildSessionStartFailure` 주석)
    const failure = buildSessionStartFailure(errorCount, error);
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
                ["다시 시도", retry],
                ["다른 단계 복습", () => router.replace("/(tabs)/learn")],
              ] as const)
            : ([
                ["다른 단계 복습", () => router.replace("/(tabs)/learn")],
                ["다시 시도", retry],
              ] as const)
          ).map(([label, onPress], order) => (
            <AppButton key={label} label={label} variant={order === 0 ? "primary" : "secondary"} onPress={onPress} />
          ))}
        </View>
      </ScreenShell>
    );
  }

  if (awaitingConfirm && conflict !== null) {
    return (
      <ScreenShell>
        <BackHeader title="진행 중인 학습" />
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
          <IeumFace size={130} accessibilityLabel="이음 캐릭터" />
          <Text
            accessibilityRole="header"
            style={{ fontSize: 26 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text, textAlign: "center" }}
          >
            {conflict.headline}
          </Text>
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 30, textAlign: "center" }}
          >
            {conflict.detail}
          </Text>
        </View>
        {/*
          이어서 풀기를 주 버튼으로 둔다. TalkBack 포커스가 먼저 닿는 자리이고, 잃는 것이
          없는 선택이기 때문이다 — 새로 시작하기는 되돌릴 수 없다
        */}
        <View style={{ gap: 12 }}>
          <AppButton
            label="이어서 풀기"
            onPress={() =>
              router.replace({
                pathname: "/session/intro",
                params: { mode: "resume", sessionId: String(inProgress!.sessionId) },
              })
            }
          />
          <AppButton
            label="새로 시작하기"
            variant="secondary"
            requireLongPress
            accessibilityHint="진행 중인 학습을 끝내고 새로 시작해요"
            onShortPress={() => announce("길게 누르면 새로 시작해요.")}
            onPress={() => setConfirmed(true)}
          />
        </View>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell>
      <BackHeader title={headerTitle} />
      <Text style={{ fontSize: 18 * fontScale, color: theme.color.textMuted }}>
        {remaining === null
          ? "불러오는 중이에요…"
          : remaining > 0
            ? `${remaining}문항 · 한 문제씩 천천히 풀어요`
            : "문제를 모두 풀었어요 · 결과만 받으면 돼요"}
      </Text>
      <View style={[styles.card, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
        {rules.map(([k, v]) => (
          <Text key={k} style={{ fontSize: 17 * fontScale, lineHeight: 27, color: theme.color.text }}>
            <Text style={{ fontWeight: "800", color: theme.color.primary }}>{k}</Text> — {v}
          </Text>
        ))}
      </View>
      <View style={{ marginTop: "auto" }}>
        {isResume ? (
          <AppButton
            label="시작하기"
            disabled={!resumeQuery.data}
            onPress={() => {
              if (resumeQuery.data) enter(resumeQuery.data);
            }}
          />
        ) : (
          <AppButton
            label={createSession.isPending ? "준비 중이에요…" : "시작하기"}
            disabled={inProgressPending || createSession.isPending}
            onPress={startNew}
          />
        )}
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, padding: 20, gap: 14 },
});
