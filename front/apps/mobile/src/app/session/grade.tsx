import { useEffect } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ordinalKo } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { useSessionStore } from "@/stores/sessionStore";
import { selectGradeFeedback } from "@/services/gradeFeedback";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V15 채점 정답 · V16 채점 오답+차이 · V17 서버 분석(문항별 칸 진단) */
export default function Grade() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const analysis = useSessionStore((s) => s.lastAnalysis);
  const local = useSessionStore((s) => s.lastResult);
  const next = useSessionStore((s) => s.next);
  const answers = useSessionStore((s) => s.answers);
  const problem = useSessionStore((s) => s.current)();
  const kind = useSessionStore((s) => s.kind);
  const disconnectShown = useSessionStore((s) => s.disconnectShown);
  const markDisconnectShown = useSessionStore((s) => s.markDisconnectShown);

  // 서버 분석(analysis)이 있으면 그것을, 없으면 기존 로컬 채점(local)을 쓴다. 실시간 수업은
  // 서버 세션이 없어 analysis가 항상 null이라 이 화면은 그대로 기존 로컬 경로로 떨어진다
  const feedback = selectGradeFeedback(analysis, local);

  useEffect(() => {
    if (!feedback) return;
    if (feedback.correct) {
      void hapticSuccess();
    } else {
      void hapticError();
    }
    // 화면 문구와 같은 문자열을 낭독한다 (서버 분석 경로는 describeCells의 불변식이 보장하고,
    // 로컬 경로는 기존 V16 낭독 문구를 그대로 유지한다)
    announce(feedback.speech);
  }, [feedback]);

  if (!session || !local || !feedback) {
    return (
      <ScreenShell style={styles.center}>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  const progress = `${index + 1} / ${session.problems.length}`;
  const lastAnswer = answers[answers.length - 1];

  const goNext = () => {
    if (next()) {
      // 수업 데모: 두 번째 문항 뒤 연결 끊김(V29)을 한 번 보여 준다 — 실연동 시 실제 이벤트로 교체
      if (kind === "class" && !disconnectShown && index + 1 === 2) {
        markDisconnectShown();
        router.replace("/class/disconnected");
        return;
      }
      router.replace(kind === "class" ? "/class/play" : "/session/play");
    } else {
      router.replace(kind === "class" ? "/class/end" : "/session/analyzing");
    }
  };

  return (
    <ScreenShell>
      <Text style={{ fontSize: 18, fontWeight: "700", color: theme.color.textMuted, fontVariant: ["tabular-nums"] }}>
        {progress}
        {feedback.correct ? "" : " · 오답"}
      </Text>
      {feedback.correct ? (
        <View style={styles.center}>
          <IeumFace size={150} accessibilityLabel="정답 캐릭터" />
          <Text accessibilityRole="header" style={{ fontSize: 40 * fontScale, fontWeight: "900", color: theme.color.primary }}>
            {feedback.headline}
          </Text>
          {/*
            서버 분석 경로의 정답 화면에는 헤드라인 말고 아무것도 붙이지 않는다. 예전에는
            규칙 설명(`feedback.tts`)을 붙였는데, 그것은 AI가 따로 채점한 결과라 서버의
            `correct`와 갈릴 수 있다 — "정답이에요!" 아래에 "3번 점이 빠졌어요"가 붙는다
            (`cellFeedback.ts`의 주석). `describeCells`가 정답일 때 detail을 비우므로
            화면과 낭독이 같은 곳에서 함께 사라진다.
          */}
          {analysis === null && problem != null && (
            <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 28, textAlign: "center" }}>
              '{problem.target}' {local.total}칸을 모두{"\n"}정확히 입력했어요.
            </Text>
          )}
        </View>
      ) : (
        <>
          <View style={{ flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between", gap: 12 }}>
            <Text accessibilityRole="header" style={{ flex: 1, fontSize: 32 * fontScale, fontWeight: "900", lineHeight: 44, color: theme.color.primary }}>
              {feedback.headline}
            </Text>
            <IeumFace size={84} expression="frown" accessibilityLabel="아쉬워하는 이음 캐릭터" />
          </View>
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 28 }}>{feedback.detail}</Text>
          {feedback.compareCell && (
            <View style={[styles.compare, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
              <BrailleCellView
                cell={feedback.compareCell.expected}
                caption={`정답 · ${ordinalKo(feedback.compareCell.index)} 칸`}
                accessibilityLabel={`정답 점자, ${ordinalKo(feedback.compareCell.index)} 칸`}
              />
              <BrailleCellView
                cell={feedback.compareCell.actual}
                emphasized
                markDots={feedback.compareCell.missingDots}
                caption={
                  feedback.compareCell.missingDots.length > 0
                    ? `내 입력 · ${feedback.compareCell.missingDots.join("·")}번 누락`
                    : `내 입력 · ${feedback.compareCell.extraDots.join("·")}번 초과`
                }
                accessibilityLabel="내가 입력한 점자"
              />
            </View>
          )}
          <View style={{ flexDirection: "row", gap: 10 }}>
            <View style={{ flex: 1 }}>
              <AppButton label="촉각으로 확인" variant="secondary" onPress={() => announce("점자 장치 연결 후에 쓸 수 있어요.")} />
            </View>
            <View style={{ flex: 1 }}>
              <AppButton label="따라 하기" variant="secondary" onPress={() => announce("따라 하기는 준비 중이에요.")} />
            </View>
          </View>
        </>
      )}
      <View style={{ marginTop: "auto" }}>
        <AppButton
          label={index + 1 >= session.problems.length ? "결과 보기" : "다음 문항"}
          onPress={goNext}
          accessibilityHint={lastAnswer ? undefined : undefined}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 28 },
  compare: { borderWidth: 1, padding: 18, flexDirection: "row", justifyContent: "space-around" },
});
