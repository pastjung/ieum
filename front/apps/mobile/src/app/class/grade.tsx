import { useEffect, useMemo, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useNavigation, useRouter } from "expo-router";
import { ordinalKo } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { IeumFace } from "@/components/IeumFace";
import { AppButton } from "@/components/AppButton";
import { BrailleCellView } from "@/components/BrailleCellView";
import { liveGradeFeedback } from "@/services/liveGradeFeedback";
import { liveLecture } from "@/services/liveLecture";
import { lectureProblemSync } from "@/services/lectureProblemSync";
import { isUserBackAction } from "@/services/lectureSignals";
import { useLectureProblemStore } from "@/stores/lectureProblemStore";
import { announce, hapticError, hapticSuccess } from "@/a11y";
import { radius, useFontScale, useTheme } from "@/theme";

/** V23b 수업 채점 — 서버가 채점한 결과를 읽어 주고, 확인하면 다음 문제를 기다린다 */
export default function ClassGrade() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const phase = useLectureProblemStore((s) => s.phase);
  const graded = phase?.kind === "graded" ? phase : null;
  const feedback = useMemo(() => (graded ? liveGradeFeedback(graded.result) : null), [graded]);
  /** 지금 보고 있는 틀린 칸의 순서 — 칸이 여럿이면 한 칸씩 넘겨 본다 */
  const [step, setStep] = useState(0);
  const attemptId = graded?.result.attemptId ?? null;

  useEffect(() => {
    setStep(0);
  }, [attemptId]);

  // 뒤로 가기로 떠나면 풀이 화면과 같이 음성 방을 정리한다
  const navigation = useNavigation();
  useEffect(
    () =>
      navigation.addListener("beforeRemove", (event) => {
        if (isUserBackAction(event.data.action)) void liveLecture.leave();
      }),
    [navigation],
  );

  // 채점 상태가 끝나면(확인 → 제출 대기, 새 문제 도착 → 풀이) 풀이 화면으로 돌아간다.
  // 새 문제 안내·대기 안내는 lectureProblemSync가 상태를 바꾸며 이미 읽었다
  useEffect(() => {
    if (!graded) router.replace("/class/play");
  }, [graded, router]);

  const steps = feedback?.steps ?? [];
  const current = steps[step] ?? steps[0];
  const isLastStep = step + 1 >= steps.length;

  useEffect(() => {
    if (!feedback || !current) return;
    // 정오 진동은 문항당 한 번이다. 칸을 넘길 때마다 울리면 칸마다 새로 채점된 것처럼 느껴진다
    if (step === 0) void (feedback.correct ? hapticSuccess() : hapticError());
    announce(current.speech);
  }, [current, feedback, step]);

  if (!graded || !feedback || !current) {
    return <ScreenShell style={styles.center} />;
  }

  return (
    <ScreenShell>
      <Text style={{ fontSize: 18, fontWeight: "700", color: theme.color.textMuted }}>
        {`선생님 문제 · ${graded.problem.displayText}`}
        {steps.length > 1 ? ` · 틀린 칸 ${step + 1} / ${steps.length}` : ""}
      </Text>
      {feedback.correct ? (
        <View style={styles.center}>
          <IeumFace size={150} accessibilityLabel="정답 캐릭터" />
          <Text accessibilityRole="header" style={{ fontSize: 40 * fontScale, fontWeight: "900", color: theme.color.primary }}>
            {feedback.headline}
          </Text>
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
        </>
      )}
      <View style={{ marginTop: "auto" }}>
        {/* 틀린 칸이 남아 있으면 먼저 한 칸씩 보여 주고, 마지막 칸에서 확인한다 — 버튼은 하나다 */}
        <AppButton
          label={isLastStep ? "확인" : "다음 점자"}
          accessibilityHint={isLastStep ? "선생님의 다음 문제를 기다려요" : "다음 틀린 칸을 보여 줘요"}
          onPress={isLastStep ? () => lectureProblemSync.acknowledgeGrade() : () => setStep((s) => s + 1)}
        />
      </View>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 28 },
  compare: { borderWidth: 1, padding: 18, flexDirection: "row", justifyContent: "space-around" },
});
