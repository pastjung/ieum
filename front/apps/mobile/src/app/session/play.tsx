import { useCallback, useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View, useWindowDimensions } from "react-native";
import { useRouter } from "expo-router";
import { cellToDots, cellsToUnicode, type Cell } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { LandscapeDotPad } from "@/components/LandscapeDotPad";
import { AppButton } from "@/components/AppButton";
import { useSessionStore } from "@/stores/sessionStore";
import { useSettingsStore } from "@/stores/settingsStore";
import { describeTooManyCells } from "@/services/answerLengthGuard";
import { submitAttempt } from "@/services/attemptSubmission";
import { learningService } from "@/services/learningService";
import { announce, hapticError, hapticTap, speak } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** 제출 뒤 서버 분석을 기다리는 동안의 화면·낭독 문구 (설계 4.1절) */
const WAITING_MESSAGE = "확인하고 있어요.";

/**
 * `LEARNING_PROBLEM_ALREADY_ANALYZED`를 받았을 때의 안내. 오류가 아니라 응답 유실(앱 재시작
 * 등)로 서버가 이미 끝낸 문항이다 — `submitAttempt` 주석 참고. 그 문항의 서버 피드백은 만들
 * 수 없으므로 다음으로 넘어간다고만 알린다.
 */
const ALREADY_MESSAGE = "이미 확인한 문제라 이번 설명은 없어요. 다음으로 넘어갈게요.";

/**
 * 잠긴 동안(대기·실패) 점자 패드를 만졌을 때 상태 문구 뒤에 붙는 말.
 *
 * 패드가 입력을 버리면서 침묵하면 그것도 "아무 일도 없었다"와 구별되지 않는다. 화면에 이미
 * 떠 있는 상태 문구에 이 말을 이어 붙여 **화면과 낭독이 같은 한 문자열**이 되게 한다.
 */
const INPUT_BLOCKED_SUFFIX = "지금은 입력할 수 없어요.";

type Phase = "idle" | "waiting" | "failed" | "exhausted";

/** V14 문제 풀이 · 세로 V패드 / V14b 가로 두 손 입력 (기기를 돌리면 전환) */
export default function SessionPlay() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const { width, height } = useWindowDimensions();
  const isLandscape = width > height;
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const submit = useSessionStore((s) => s.submit);
  const problem = useSessionStore((s) => s.current)();
  const serverSessionId = useSessionStore((s) => s.serverSessionId);
  const problemSessionIds = useSessionStore((s) => s.problemSessionIds);
  const setLastAnalysis = useSessionStore((s) => s.setLastAnalysis);
  const setLastAttemptNotice = useSessionStore((s) => s.setLastAttemptNotice);
  const isScreenMode = useSettingsStore((s) => s.mode) === "screen";
  const [cells, setCells] = useState<Cell[]>([]);
  // 제출이 막힌 이유 / 대기 중 / 실패 안내. 낭독만으로는 전달되지 않는다 — 음성·진동은
  // 설정에서 끌 수 있고(`onboarding/accessibility.tsx`), 꺼져 있으면 `announce`도
  // `hapticError`도 아무 일을 하지 않는다. 그러면 길게 눌러도 화면이 그대로여서 무슨 일이
  // 있었는지 알 방법이 없다. 이 브랜치의 다른 화면(`intro.tsx`·`analyzing.tsx`)과 같은
  // 규칙으로 화면에도 띄운다 — 화면 문구와 낭독 문구는 같은 문자열이다.
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  // idle: 입력 가능. waiting: 서버 분석 대기, 입력·제출 잠금. failed: 재시도 대기, 입력 잠금.
  // exhausted: MAX_ATTEMPT_TRIES 소진, 전용 화면으로 대체된다.
  const [phase, setPhase] = useState<Phase>("idle");
  const locked = phase !== "idle";
  // 잠긴 동안 패드를 만졌는지. 켜지면 상태 문구에 INPUT_BLOCKED_SUFFIX가 붙어 화면과 낭독에
  // 함께 나간다. 상태(phase)가 바뀌면 그 사실도 더 이상 지금의 것이 아니므로 아래 effect가 끈다
  const [inputBlocked, setInputBlocked] = useState(false);
  // 문항마다 재시도 횟수를 새로 센다. `submitAttempt`가 시도 횟수로 실패 문구를 바꿔
  // `speak()`의 1.5초 중복 제거를 피한다(`attemptSubmission.ts` 주석 참고).
  const attemptRef = useRef(0);
  // `analyzing.tsx`의 `mountedRef`와 같은 이유: 이 화면은 뒤로 가기를 막지 않으므로
  // (`session/_layout.tsx`가 없다) 응답을 기다리는 동안 화면을 나갈 수 있다. 그 뒤 늦게
  // 도착한 응답이 사용자가 지금 보고 있는 다른 화면을 튕겨내면 안 된다.
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    setCells([]);
    setStatusMessage(null);
    setPhase("idle");
    attemptRef.current = 0;
    if (problem) announce(`${index + 1}번 문제. ${problem.prompt}`);
  }, [problem, index]);

  // 상태가 바뀌면 "입력이 무시됐다"는 안내도 지금의 사실이 아니다
  useEffect(() => {
    setInputBlocked(false);
  }, [phase]);

  const commit = useCallback((cell: Cell) => {
    setCells((prev) => [...prev, cell]);
    // 입력이 달라졌으니 막힌 이유도 더 이상 사실이 아니다
    setStatusMessage(null);
    void hapticTap();
  }, []);

  // 화면에 그리는 문구 = 낭독하는 문구. 잠긴 동안 패드를 만졌으면 그 사실을 상태 문구에
  // 이어 붙인다 (설계 8절 — 두 채널이 한 문자열을 공유한다)
  const displayedStatus =
    statusMessage === null
      ? inputBlocked
        ? INPUT_BLOCKED_SUFFIX
        : null
      : inputBlocked
        ? `${statusMessage} ${INPUT_BLOCKED_SUFFIX}`
        : statusMessage;

  /**
   * 잠긴 동안 점자 패드를 만졌다.
   *
   * 예전에는 `commit`이 `if (locked) return;`으로 조용히 버렸는데, 패드에는 잠금 개념이
   * 없어서 `useMultiTouchDots`가 점마다 진동을 내고 확정 시 "3번 입력"까지 낭독했다.
   * 시각장애인 사용자는 그 신호를 입력이 기록됐다는 뜻으로 읽는다 — 실패 상태에서 답을
   * 고치려던 사용자가 고쳤다고 믿고 "다시 보내기"를 누르면 **옛 답이 전송된다.**
   *
   * 이제 잠금을 패드까지 내려보내 터치 자체를 무시하고, 무시했다는 사실과 그 이유를
   * 화면·낭독에 같은 문자열로 한 번 내보낸다.
   */
  const onBlockedTouch = useCallback(() => {
    setInputBlocked(true);
    void hapticError();
    announce(
      statusMessage === null
        ? INPUT_BLOCKED_SUFFIX
        : `${statusMessage} ${INPUT_BLOCKED_SUFFIX}`,
    );
  }, [statusMessage]);

  if (!session || !problem) {
    return (
      <ScreenShell style={styles.center}>
        <Text style={{ color: theme.color.text, fontSize: 20, fontWeight: "700" }}>진행 중인 학습이 없어요</Text>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  // 시도를 소진했다 — 전용 화면으로 대체한다. 재시도해도 성공할 수 없는 실패가 실재하므로
  // (12칸 초과 문제 등) 입력·재시도를 계속 보여 주면 사용자를 이 화면에 가둔다.
  if (phase === "exhausted") {
    return (
      <ScreenShell style={styles.center}>
        <Text
          accessibilityRole="header"
          accessibilityLiveRegion="polite"
          style={{ fontSize: 22 * fontScale, fontWeight: "800", lineHeight: 32, color: theme.color.text, textAlign: "center" }}
        >
          {displayedStatus}
        </Text>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  /** 제출 버튼 짧은 터치: 지금까지 입력한 점을 읽어 준다 */
  const readInput = () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요. 점을 짚고 손을 떼면 한 칸이 입력돼요.");
      return;
    }
    const last = cellToDots(cells[cells.length - 1]!);
    announce(
      `지금까지 ${cells.length}칸 입력했어요. 마지막 칸은 ${last.join("번, ")}번 점이에요. 길게 누르면 제출돼요.`,
    );
  };

  /**
   * 문항 하나를 서버로 보내고 응답에 따라 화면을 옮긴다(설계 4.1절).
   *
   * 로컬 기록(`submit`)은 여기서 하지 않는다 — 재시도가 다시 이 함수를 부르므로, 여기서
   * 기록하면 같은 답이 `answers[]`에 여러 번 쌓여 `correctCount()`가 부풀려진다. 로컬 기록은
   * 첫 제출(`submitAnswer`)에서 한 번만 한다.
   */
  const runAttempt = async (inputPatterns: Cell[]) => {
    if (serverSessionId === null) return;
    const problemSessionId = problemSessionIds[index];
    // 정상 경로에서는 일어나지 않는다 — 서버 세션인데 배정 식별자가 없다면 이 문항은 서버가
    // 모르는 것이니 보낼 수 없다. 결과 화면은 로컬 채점 결과를 그대로 보여 준다.
    if (problemSessionId === undefined) {
      router.replace("/session/grade");
      return;
    }

    attemptRef.current += 1;
    const attempt = attemptRef.current;
    setPhase("waiting");
    setStatusMessage(WAITING_MESSAGE);
    announce(WAITING_MESSAGE);

    const outcome = await submitAttempt({
      analyze: learningService.analyzeAttempt,
      sessionId: serverSessionId,
      problemSessionId,
      inputPatterns,
      attempt,
    });

    // 화면을 떠난 뒤 도착한 응답이다 — 상태를 바꾸거나 다른 화면으로 튕겨내지 않는다
    if (!mountedRef.current) return;

    switch (outcome.kind) {
      case "analyzed":
        setLastAttemptNotice(null);
        setLastAnalysis(outcome.result);
        setPhase("idle");
        setStatusMessage(null);
        router.replace("/session/grade");
        return;
      case "already":
        // 이 화면에 띄워 봐야 소용이 없다 — 바로 다음 줄의 router.replace가 화면을 치우고,
        // 낭독도 grade.tsx의 announce가 Speech.stop()으로 끊는다. 안내를 스토어에 실어
        // 보내 grade 화면이 화면·낭독 한 문자열로 내보내게 한다.
        //
        // 그 화면은 lastAnalysis가 null이라 로컬 채점으로 떨어지는데, 설계 3절이 정답
        // 여부는 더 이상 로컬이 정하지 않는다고 못 박았다 — 그래서 grade.tsx는 이 안내가
        // 있으면 로컬 정오 판정을 헤드라인으로 쓰지 않는다.
        setLastAttemptNotice(ALREADY_MESSAGE);
        setPhase("idle");
        router.replace("/session/grade");
        return;
      case "failed":
        void hapticError();
        setPhase("failed");
        setStatusMessage(outcome.message);
        announce(outcome.message);
        return;
      case "exhausted":
        void hapticError();
        setPhase("exhausted");
        setStatusMessage(outcome.message);
        announce(outcome.message);
        return;
    }
  };

  const submitAnswer = () => {
    if (locked) return;
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요. 점을 짚고 손을 떼면 한 칸이 입력돼요.");
      return;
    }
    // AI 분석은 12칸까지만 받는다. 기대 칸 수와는 무관하다(`answerLengthGuard.ts` 주석) —
    // 기대 칸 수 초과는 이제 막지 않고 서버가 오답으로 채점한다.
    const tooMany = describeTooManyCells(cells.length);
    if (tooMany) {
      void hapticError();
      // 화면 문구와 낭독 문구는 같은 문자열이다 (설계 8절)
      setStatusMessage(tooMany);
      announce(tooMany);
      return;
    }
    setStatusMessage(null);
    // 수업 경로와 correctCount()가 이 로컬 기록을 쓴다. 서버 분석 성패와 무관하게 한 번만 한다
    submit(cells);

    // 목 세션(serverSessionId === null)은 지금처럼 바로 결과 화면으로 간다 — 실시간 수업과
    // 목 데이터 경로는 서버 분석을 받지 않는다.
    if (serverSessionId === null) {
      router.replace("/session/grade");
      return;
    }
    void runAttempt(cells);
  };

  const retry = () => {
    // `locked`(phase !== "idle")를 그대로 쓰면 retry는 정확히 phase === "failed"일 때만
    // 눌리는데 그 상태도 locked === true라 항상 막힌다. 여기서 막아야 하는 것은 "failed가
    // 아닐 때"(요청이 아직 진행 중인 waiting, 이미 넘어간 idle/exhausted)의 연타다.
    if (phase !== "failed") return;
    void runAttempt(cells);
  };

  const eraseLast = () => {
    if (locked) return;
    setStatusMessage(null);
    setCells((prev) => {
      if (prev.length === 0) {
        announce("지울 칸이 없어요.");
        return prev;
      }
      announce("한 칸 지웠어요.");
      return prev.slice(0, -1);
    });
  };

  /**
   * 재시도 버튼의 짧은 터치. "입력 완료"의 `onShortPress`(`readInput`)와 대칭을 맞춘다 —
   * 이 화면은 짧게 눌러 확인하고 길게 눌러 실행하는 관행을 쓰므로, 짧은 터치가 아무 반응이
   * 없으면 사용자는 버튼이 고장난 줄 안다. 실패 문구(`statusMessage`)를 다시 읽어 줘 놓친
   * 사용자가 왜 이 화면에 있는지 확인할 수 있게 하고, 길게 누르면 무슨 일이 일어나는지도
   * 이어서 말한다.
   */
  const readRetryStatus = () => {
    announce(`${statusMessage ?? "방금 시도가 실패했어요."} 길게 누르면 방금 입력한 답을 다시 보내요.`);
  };

  // 재시도도 "입력 완료"와 같은 길게 누르기다 — MAX_ATTEMPT_TRIES(3)는 희소 자원이라 실수로
  // 누르면 세 번째에 12문항을 처음부터 다시 풀어야 한다. 같은 화면에서 어떤 버튼은 짧게, 어떤
  // 버튼은 길게 누르면 한 번 익힌 조작 모델이 깨진다.
  const submitButtonProps =
    phase === "failed"
      ? {
          label: "다시 보내기",
          requireLongPress: true,
          accessibilityHint: "길게 누르면 방금 입력한 답을 다시 보내요.",
          onPress: retry,
          onShortPress: readRetryStatus,
        }
      : { label: "길게 눌러 제출", requireLongPress: true, onPress: submitAnswer, onShortPress: readInput };

  // V14b 가로 두 손 입력 — 왼손 3·2·1, 오른손 6·5·4
  if (isLandscape) {
    return (
      <ScreenShell>
        <View style={styles.topRow}>
          <Text accessibilityRole="header" style={{ fontSize: 17 * fontScale, fontWeight: "800", color: theme.color.text, flexShrink: 1 }}>
            {problem.prompt}
          </Text>
          <Text
            accessibilityLabel={`${session.problems.length}문제 중 ${index + 1}번`}
            style={{ fontSize: 18, fontWeight: "700", color: theme.color.text, fontVariant: ["tabular-nums"] }}
          >
            {index + 1} / {session.problems.length}
          </Text>
          <AppButton label="나가기" variant="compact" accessibilityHint="학습을 중단하고 홈으로 가요" onPress={() => router.replace("/(tabs)")} />
        </View>
        <LandscapeDotPad
          onCommitCell={commit}
          disabled={locked}
          onBlockedTouch={onBlockedTouch}
          center={
            <>
              <View style={{ flex: 1 }}>
                <AppButton label={"다시\n듣기"} variant="compact" onPress={() => speak(problem.prompt, { force: true })} />
              </View>
              <View style={{ flex: 1 }}>
                <AppButton label="지우기" variant="compact" disabled={locked} onPress={eraseLast} />
              </View>
              <View style={{ flex: 1.4 }}>
                <AppButton {...submitButtonProps} disabled={phase === "waiting"} />
              </View>
            </>
          }
        />
        {displayedStatus !== null && (
          <Text
            accessibilityLiveRegion="polite"
            style={{
              fontSize: 15 * fontScale,
              lineHeight: 22,
              color: theme.color.text,
              backgroundColor: theme.color.surface,
              borderWidth: 2,
              borderColor: theme.color.primary,
              borderRadius: radius.card,
              paddingHorizontal: 12,
              paddingVertical: 8,
              textAlign: "center",
            }}
          >
            {displayedStatus}
          </Text>
        )}
        <Text style={{ fontSize: 13, color: theme.color.textMuted, textAlign: "center" }}>
          양손 파지 — 왼손 3·2·1, 오른손 6·5·4
        </Text>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell>
      <View style={styles.topRow}>
        <AppButton label="나가기" variant="compact" accessibilityHint="학습을 중단하고 홈으로 가요" onPress={() => router.replace("/(tabs)")} />
        <Text
          accessibilityLabel={`${session.problems.length}문제 중 ${index + 1}번`}
          style={{ fontSize: 22, fontWeight: "700", color: theme.color.text, fontVariant: ["tabular-nums"] }}
        >
          {index + 1} / {session.problems.length}
        </Text>
        <Text
          accessibilityLabel={`지금까지 ${cells.length}칸 입력. 기기를 가로로 돌리면 두 손 입력이 돼요.`}
          style={{ fontSize: 18, color: theme.color.textMuted }}
        >
          {cells.length > 0 ? `${cellsToUnicode(cells)} · ${cells.length}칸` : "입력 전"}
        </Text>
      </View>
      <Text accessibilityRole="header" style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        {problem.prompt}
      </Text>
      {isScreenMode && (
        <View
          style={{
            minHeight: 64,
            borderWidth: 2,
            borderColor: theme.color.border,
            borderRadius: 14,
            alignItems: "center",
            justifyContent: "center",
            paddingHorizontal: 12,
          }}
          accessibilityLabel={`입력한 점자 ${cells.length}칸`}
        >
          <Text style={{ fontSize: 40, color: theme.color.text, letterSpacing: 6 }}>
            {cells.length > 0 ? cellsToUnicode(cells) : "⠀"}
          </Text>
        </View>
      )}
      {displayedStatus !== null && (
        <Text
          accessibilityLiveRegion="polite"
          style={{
            fontSize: 17 * fontScale,
            lineHeight: 26,
            color: theme.color.text,
            backgroundColor: theme.color.surface,
            borderWidth: 2,
            borderColor: theme.color.primary,
            borderRadius: radius.card,
            paddingHorizontal: 14,
            paddingVertical: 10,
          }}
        >
          {displayedStatus}
        </Text>
      )}
      <View style={{ flexDirection: "row", gap: 10 }}>
        <View style={{ flex: 1 }}>
          <AppButton label="문제 다시 듣기" variant="compact" onPress={() => speak(problem.prompt, { force: true })} />
        </View>
        <View style={{ flex: 1 }}>
          <AppButton label="한 칸 지우기" variant="compact" accessibilityHint="마지막에 입력한 칸을 지워요" disabled={locked} onPress={eraseLast} />
        </View>
      </View>
      <DotPad onCommitCell={commit} disabled={locked} onBlockedTouch={onBlockedTouch} />
      <AppButton {...submitButtonProps} disabled={phase === "waiting"} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 16 },
  topRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 10 },
});
