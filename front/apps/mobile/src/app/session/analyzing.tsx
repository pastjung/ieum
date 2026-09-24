import { useCallback, useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { useSessionStore } from "@/stores/sessionStore";
import { learningService } from "@/services/learningService";
import { createSubmissionDeduper, buildRetryFailureMessage } from "@/services/learningSessionSubmission";
import { describeAuthError } from "@/services/authErrors";
import { announce, hapticError, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/**
 * 모듈 스코프에 딱 하나. 이 화면은 뒤로 가기를 막지 않는다(`session/_layout.tsx`가 없어
 * 스와이프 백·하드웨어 백이 모두 열려 있다). 제출 중에 나갔다가 같은 흐름을 다시 타면
 * (`grade.tsx`가 완료된 세션을 다시 이 화면으로 보낸다) 새 컴포넌트 인스턴스가 마운트되며
 * `submit()`이 또 실행되는데, `useRef`는 인스턴스에 묶여 재마운트되면 초기화되므로 "같은
 * 제출이 아직 진행 중"이라는 사실을 컴포넌트 안에서는 표현할 수 없다 — 그래서 모듈 스코프에
 * 둔다. 자세한 이유는 `createSubmissionDeduper` 주석 참고.
 */
const dedupedSubmit = createSubmissionDeduper();

/** V17 분석 중 */
export default function Analyzing() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const serverSessionId = useSessionStore((s) => s.serverSessionId);
  const buildAttempts = useSessionStore((s) => s.buildAttempts);
  const setServerResult = useSessionStore((s) => s.setServerResult);
  const [failed, setFailed] = useState<string | null>(null);
  // 이 인스턴스가 화면에서 사라진 뒤 늦게 도착한 응답이 사용자가 지금 보고 있는 다른
  // 화면을 결과 화면으로 튕겨내지 않도록 막는다.
  const mountedRef = useRef(true);
  // 재시도마다 다른 문구를 만들기 위한 시도 횟수. `buildRetryFailureMessage` 주석 참고 —
  // 같은 문구가 1.5초 안에 반복되면 낭독이 조용히 씹힌다.
  const attemptRef = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const submit = useCallback(async () => {
    setFailed(null);
    attemptRef.current += 1;
    const attempt = attemptRef.current;
    // 낭독은 제출과 나란히 간다. 낭독이 늦어도(또는 목 세션이라 제출이 없어도) 화면 전환을 막지 않는다
    void speakAsync("학습 완료! 결과를 분석 중이에요. 잠시만 기다려 주세요.");
    const outcome = await dedupedSubmit({
      serverSessionId,
      attempts: buildAttempts(),
      completeSession: learningService.completeSession,
    });

    // 화면을 떠난 뒤 도착한 응답이다 — 상태를 바꾸거나 다른 화면으로 튕겨내지 않는다
    if (!mountedRef.current) return;

    if (outcome.kind === "failed") {
      void hapticError();
      const message = buildRetryFailureMessage(attempt, describeAuthError(outcome.error));
      setFailed(message);
      // 결과 화면으로 넘기지 않는다. 넘기면 사용자가 푼 것이 조용히 사라진다
      announce(message);
      return;
    }
    if (outcome.kind === "success") {
      setServerResult(outcome.result);
    }
    // "skipped"(목 세션)이면 제출할 것이 없었을 뿐이다 — 결과 화면은 그대로 보여 준다
    router.replace("/session/summary");
  }, [serverSessionId, buildAttempts, setServerResult, router]);

  useEffect(() => {
    void submit();
  }, [submit]);

  if (failed !== null) {
    return (
      <ScreenShell>
        <View style={{ flex: 1, alignItems: "center", justifyContent: "center", gap: 18 }}>
          <Text
            accessibilityRole="header"
            style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 40, color: theme.color.text, textAlign: "center" }}
          >
            결과를 보내지{"\n"}못했어요
          </Text>
          <Text
            accessibilityLiveRegion="polite"
            style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 29, textAlign: "center" }}
          >
            {failed}
          </Text>
        </View>
        <View style={{ gap: 12 }}>
          <AppButton label="다시 보내기" onPress={() => void submit()} />
          {/* 재시도가 구조적으로 성공할 수 없는 실패(만료된 세션·계정 문제)도 있다. 탈출구가
              없으면 음성으로만 쓰는 사용자는 이 화면에 갇힌다 — `intro.tsx`의 실패 화면과
              같은 규칙으로 둘째 버튼을 둔다 */}
          <AppButton label="홈으로" variant="secondary" onPress={() => router.replace("/(tabs)")} />
        </View>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell style={styles.center}>
      <IeumFace size={130} expression="worried" accessibilityLabel="분석 중 캐릭터" />
      <Text accessibilityRole="header" style={{ fontSize: 30 * fontScale, fontWeight: "900", lineHeight: 42, color: theme.color.text, textAlign: "center" }}>
        학습 완료!{"\n"}결과를 분석 중이에요
      </Text>
      {/* 이어하기가 없다(설계 9절) — `sessionId`를 기기에 저장하지 않고 답안은 스토어
          메모리에만 있다. "앱을 닫아도 괜찮다"고 안내하면 12문항이 전부 사라진다 */}
      <Text style={{ fontSize: 16 * fontScale, color: theme.color.textMuted, lineHeight: 27, textAlign: "center" }}>
        결과를 보내는 중이에요.{"\n"}앱을 닫으면 처음부터 다시 풀어야 해요.
      </Text>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 22 },
});
