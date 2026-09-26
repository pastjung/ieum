import { useEffect, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { ordinalKo } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { useSessionStore } from "@/stores/sessionStore";
import { selectGradeFeedback } from "@/services/gradeFeedback";
import { announce, hapticError, hapticSuccess, hapticTap } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V15 채점 정답 · V16 채점 오답+차이 · V17 서버 분석(문항별 칸 진단) */
export default function Grade() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const analysis = useSessionStore((s) => s.lastAnalysis);
  const notice = useSessionStore((s) => s.lastAttemptNotice);
  const local = useSessionStore((s) => s.lastResult);
  const next = useSessionStore((s) => s.next);
  const problem = useSessionStore((s) => s.current)();
  const kind = useSessionStore((s) => s.kind);

  // 서버 분석(analysis)이 있으면 그것을, 없으면 기존 로컬 채점(local)을 쓴다. 실시간 수업은
  // 서버 세션이 없어 analysis가 항상 null이라 이 화면은 그대로 기존 로컬 경로로 떨어진다
  const feedback = selectGradeFeedback(analysis, local);

  /**
   * 지금 보고 있는 틀린 칸의 순서. 단어처럼 칸이 여럿인 문항은 한 칸씩 넘겨 가며 본다 —
   * 화면에는 그 칸의 정답·내 입력만 그리고, 낭독도 그 칸만 말한다.
   */
  const [step, setStep] = useState(0);

  // 문항이 바뀌면 처음 칸부터 다시 본다. 이 초기화가 없으면 3칸이 틀렸던 앞 문항에서
  // step 2에 머문 채 다음 문항으로 넘어가 범위 밖을 가리킨다
  useEffect(() => {
    setStep(0);
  }, [index]);

  const steps = feedback?.steps ?? [];
  // step이 범위를 벗어나는 순간(위 초기화가 도는 렌더 사이)에도 무언가는 그려야 한다
  const current = steps[step] ?? steps[0];
  const isLastStep = step + 1 >= steps.length;

  useEffect(() => {
    // 서버가 이미 끝낸 문항이라 이번 설명이 없다. 정오 진동도 내지 않는다 — 그 판정은
    // 로컬 채점에서만 나오고, 설계 3절이 정답 여부는 더 이상 로컬이 정하지 않는다고
    // 못 박았다. 대신 중립적인 촉각 신호로 "무언가 일어났다"는 것만 알린다
    if (notice !== null) {
      void hapticTap();
      announce(notice);
      return;
    }
    if (!feedback || !current) return;
    // 정오 진동은 문항당 한 번이다. 칸을 넘길 때마다 울리면 칸마다 새로 채점된 것처럼 느껴진다
    if (step === 0) {
      if (feedback.correct) {
        void hapticSuccess();
      } else {
        void hapticError();
      }
    }
    // 화면 문구와 같은 문자열을 낭독한다 (서버 분석 경로는 describeCells의 불변식이 보장하고,
    // 로컬 경로는 기존 V16 낭독 문구를 그대로 유지한다)
    announce(current.speech);
  }, [current, feedback, notice, step]);

  if (!session || !local || !feedback || !current) {
    return (
      <ScreenShell style={styles.center}>
        <AppButton label="홈으로" onPress={() => router.replace("/(tabs)")} />
      </ScreenShell>
    );
  }

  const progress = `${index + 1} / ${session.problems.length}`;

  const goNext = () => {
    if (next()) {
      router.replace(kind === "class" ? "/class/play" : "/session/play");
    } else {
      router.replace(kind === "class" ? "/class/end" : "/session/analyzing");
    }
  };

  // 서버가 이미 끝낸 문항(LEARNING_PROBLEM_ALREADY_ANALYZED). 이 경로는 lastAnalysis가
  // null이라 feedback이 로컬 분기로 떨어지는데, 그 정오 판정을 헤드라인으로 쓰면 서버가
  // 기록한 결과와 다른 것을 사실처럼 크게 보여줄 수 있다. 안내 한 문장만 내보낸다 —
  // 화면에 그리는 문자열과 위 effect가 낭독하는 문자열이 같은 값이다.
  if (notice !== null) {
    return (
      <ScreenShell>
        <Text style={{ fontSize: 18, fontWeight: "700", color: theme.color.textMuted, fontVariant: ["tabular-nums"] }}>
          {progress}
        </Text>
        <View style={styles.center}>
          <IeumFace size={130} accessibilityLabel="이음 캐릭터" />
          <Text
            accessibilityRole="header"
            accessibilityLiveRegion="polite"
            style={{ fontSize: 26 * fontScale, fontWeight: "800", lineHeight: 38, color: theme.color.text, textAlign: "center" }}
          >
            {notice}
          </Text>
        </View>
        <View style={{ marginTop: "auto" }}>
          <AppButton label={index + 1 >= session.problems.length ? "결과 보기" : "다음 문항"} onPress={goNext} />
        </View>
      </ScreenShell>
    );
  }

  return (
    <ScreenShell>
      <Text style={{ fontSize: 18, fontWeight: "700", color: theme.color.textMuted, fontVariant: ["tabular-nums"] }}>
        {progress}
        {feedback.correct ? "" : " · 오답"}
        {steps.length > 1 ? ` · 틀린 칸 ${step + 1} / ${steps.length}` : ""}
      </Text>
      {feedback.correct ? (
        <View style={styles.center}>
          <IeumFace size={150} accessibilityLabel="정답 캐릭터" />
          <Text accessibilityRole="header" style={{ fontSize: 40 * fontScale, fontWeight: "900", color: theme.color.primary }}>
            {feedback.headline}
          </Text>
          {/*
            서버 분석 경로의 정답 화면에는 헤드라인 말고 아무것도 붙이지 않는다. 예전에는
            규칙 설명(`feedback.detail`)을 붙였는데, 그것은 AI가 따로 채점한 결과라 서버의
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
          <Text style={{ fontSize: 17 * fontScale, color: theme.color.textMuted, lineHeight: 28 }}>{current.detail}</Text>
          {current.compare && (
            <View style={[styles.compare, { backgroundColor: theme.color.surface, borderColor: theme.color.border, borderRadius: radius.card }]}>
              <BrailleCellView
                cell={current.compare.expected}
                caption={`정답 · ${ordinalKo(current.compare.index)} 칸`}
                accessibilityLabel={`정답 점자, ${ordinalKo(current.compare.index)} 칸`}
              />
              <BrailleCellView
                cell={current.compare.actual}
                emphasized
                markDots={current.compare.missingDots}
                caption={
                  current.compare.missingDots.length > 0
                    ? `내 입력 · ${current.compare.missingDots.join("·")}번 누락`
                    : `내 입력 · ${current.compare.extraDots.join("·")}번 초과`
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
        {/*
          틀린 칸이 남아 있으면 먼저 그 칸들을 하나씩 보여 준다. 마지막 칸에서야 다음
          문항으로 넘어간다 — 버튼이 하나라 학습자가 "지금 무엇을 누르는지" 헷갈리지 않는다
        */}
        <AppButton
          label={
            isLastStep
              ? index + 1 >= session.problems.length
                ? "결과 보기"
                : "다음 문항"
              : "다음 점자"
          }
          onPress={isLastStep ? goNext : () => setStep((s) => s + 1)}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 28 },
  compare: { borderWidth: 1, padding: 18, flexDirection: "row", justifyContent: "space-around" },
});
