import { useEffect, useRef, useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useNavigation, useRouter } from "expo-router";
import type { Cell } from "@ieum/domain";
import { cellToDots, cellsToUnicode } from "@ieum/domain";
import { ScreenShell } from "@/components/ScreenShell";
import { DotPad } from "@/components/DotPad";
import { AppButton } from "@/components/AppButton";
import { liveLecture } from "@/services/liveLecture";
import { isUserBackAction } from "@/services/lectureSignals";
import { ensureMicPermission } from "@/services/micPermission";
import {
  HAND_DOWN_MESSAGE,
  HAND_FAILED_MESSAGE,
  HAND_UP_MESSAGE,
  lectureVoiceControls,
  MIC_FAILED_MESSAGE,
  MIC_OFF_MESSAGE,
  MIC_ON_MESSAGE,
  MIC_PERMISSION_DENIED_MESSAGE,
  setLecturePlayScreenActive,
} from "@/services/lectureVoiceControls";
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticSuccess, speak, speakAsync } from "@/a11y";
import { useFontScale, useTheme } from "@/theme";

/** TalkBack의 실제 낭독 속도가 추정보다 느릴 때를 대비한 여유 (M4) */
const MIC_ON_ANNOUNCE_MARGIN_MS = 400;

/** V23 수업 진행 · 풀이 */
export default function ClassPlay() {
  const router = useRouter();
  const theme = useTheme();
  const fontScale = useFontScale();
  const session = useSessionStore((s) => s.session);
  const index = useSessionStore((s) => s.index);
  const submit = useSessionStore((s) => s.submit);
  const problem = useSessionStore((s) => s.current)();
  const [cells, setCells] = useState<Cell[]>([]);
  // 발언 상태는 liveLecture가 스토어에 쓴다. 객체로 한 번에 고르면 매번 새 참조라 값별로 고른다
  const speakingAllowed = useLectureVoiceStore((s) => s.speakingAllowed);
  const micOn = useLectureVoiceStore((s) => s.micOn);
  const handUp = useLectureVoiceStore((s) => s.handUp);
  const voice = lectureVoiceControls({ speakingAllowed, micOn, handUp });
  // 손들기·마이크 요청이 진행 중이다 — 겹쳐 누른 것을 무시한다 (버튼을 비활성으로 바꾸면 TalkBack이 "사용할 수 없음"을 끼워 읽는다)
  const voiceBusyRef = useRef(false);

  // 하드웨어 뒤로 가기·제스처로 떠나면 음성 방을 먼저 정리한다. 뒤로 가면 대기 화면을 열었던
  // 수업 탭이 나온다(대기 화면은 이 화면으로 replace됐다). 채점 화면으로의 이동은
  // router.replace(REPLACE)라 방을 그대로 둔다
  const navigation = useNavigation();
  useEffect(
    () =>
      navigation.addListener("beforeRemove", (event) => {
        if (isUserBackAction(event.data.action)) void liveLecture.leave();
      }),
    [navigation],
  );

  useEffect(() => {
    setCells([]);
    if (problem) announce(`선생님 문제예요. ${problem.prompt}`);
  }, [problem]);

  // 마이크 버튼이 있는 유일한 화면이다 — 떠 있는 동안만 onSpeakingChanged가 "버튼을 눌러 켜세요"라고 말한다 (M5)
  useEffect(() => {
    setLecturePlayScreenActive(true);
    return () => setLecturePlayScreenActive(false);
  }, []);

  if (!session || !problem) {
    return (
      <ScreenShell style={styles.center}>
        <Text style={{ color: theme.color.text, fontSize: 20, fontWeight: "700" }}>진행 중인 수업이 없어요</Text>
        <AppButton
          label="수업 참여로"
          onPress={() => {
            void liveLecture.leave();
            router.replace("/(tabs)/class");
          }}
        />
      </ScreenShell>
    );
  }

  const dot = (color: string) => (
    <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: color }} />
  );

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

  const submitAnswer = async () => {
    if (cells.length === 0) {
      announce("아직 입력한 칸이 없어요.");
      return;
    }
    // 채점 화면·이후 화면에는 마이크 버튼이 없다 — 켠 채로 넘어가면 끌 방법이 없어
    // 채점 결과 낭독이 그대로 반 전체에 전해진다
    if (micOn) await liveLecture.setMicrophone(false);
    submit(cells);
    router.replace("/session/grade");
  };

  const toggleHand = async () => {
    if (voiceBusyRef.current) return;
    voiceBusyRef.current = true;
    const up = !handUp;
    const ok = await liveLecture.raiseHand(up);
    voiceBusyRef.current = false;
    announce(ok ? (up ? HAND_UP_MESSAGE : HAND_DOWN_MESSAGE) : HAND_FAILED_MESSAGE);
  };

  const toggleMic = async () => {
    if (voiceBusyRef.current) return;
    voiceBusyRef.current = true;
    try {
      if (micOn) {
        const ok = await liveLecture.setMicrophone(false);
        announce(ok ? MIC_OFF_MESSAGE : MIC_FAILED_MESSAGE);
        return;
      }
      if (!(await ensureMicPermission())) {
        announce(MIC_PERMISSION_DENIED_MESSAGE);
        return;
      }
      // 켠 뒤에 읽으면 안내 음성이 수업에 그대로 전해진다 — 켜기 전에 말하고, 켜진 것은 진동으로 알린다.
      // TalkBack이 켜져 있으면 speakAsync는 글자 수로 낭독 시간을 어림잡을 뿐이라 TalkBack 자신의
      // 말하기 속도가 느리면 추정보다 낭독이 늦게 끝난다 — 여유 400ms를 더 기다린 뒤에 연다
      await speakAsync(MIC_ON_MESSAGE);
      await new Promise((resolve) => setTimeout(resolve, MIC_ON_ANNOUNCE_MARGIN_MS));
      const ok = await liveLecture.setMicrophone(true);
      if (ok) void hapticSuccess();
      // 그 사이 선생님이 거뒀다면 회수 안내가 이미 나갔다 — 실패 안내를 겹쳐 읽지 않는다
      else if (useLectureVoiceStore.getState().speakingAllowed) announce(MIC_FAILED_MESSAGE);
    } finally {
      voiceBusyRef.current = false;
    }
  };

  return (
    <ScreenShell>
      <View style={styles.statusRow}>
        <View
          style={styles.statusItem}
          accessibilityLabel={`수업 중, 선생님 음성 연결됨${voice.status ? `, ${voice.status}` : ""}`}
        >
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 16, fontWeight: "800", color: theme.color.text }}>수업 중</Text>
          <Text style={{ fontSize: 14, color: theme.color.textMuted, marginLeft: 6 }}>
            {voice.status || "선생님 음성"}
          </Text>
        </View>
        <View
          style={[styles.chip, { borderColor: theme.color.border }]}
          accessibilityLabel="점자 장치 연결됨"
        >
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 14, fontWeight: "700", color: theme.color.text }}>장치 연결됨</Text>
        </View>
      </View>
      <View style={{ flexDirection: "row", gap: 10 }}>
        {/* 두 버튼은 늘 같은 자리·같은 높이다 — 상태가 바뀌어도 오른쪽 마이크 버튼의 글자만 바뀐다 */}
        <View style={{ flex: 1 }}>
          <AppButton
            label={voice.hand.label}
            variant="compact"
            accessibilityHint={voice.hand.hint}
            disabled={voice.hand.disabled}
            onPress={() => void toggleHand()}
          />
        </View>
        <View style={{ flex: 1 }}>
          <AppButton
            label={voice.mic.label}
            variant="compact"
            accessibilityHint={voice.mic.hint}
            disabled={voice.mic.disabled}
            onPress={() => void toggleMic()}
          />
        </View>
      </View>
      <Text accessibilityRole="header" style={{ fontSize: 28 * fontScale, fontWeight: "900", lineHeight: 38, color: theme.color.text }}>
        {problem.prompt}
      </Text>
      <View style={{ flexDirection: "row", gap: 10 }}>
        <View style={{ flex: 1 }}>
          <AppButton label="문제 다시 듣기" variant="compact" onPress={() => speak(problem.prompt, { force: true })} />
        </View>
        <View style={{ flex: 1 }}>
          <AppButton
            label="한 칸 지우기"
            variant="compact"
            onPress={() =>
              setCells((prev) => {
                if (prev.length === 0) {
                  announce("지울 칸이 없어요.");
                  return prev;
                }
                announce("한 칸 지웠어요.");
                return prev.slice(0, -1);
              })
            }
          />
        </View>
      </View>
      <Text
        accessibilityLabel={`지금까지 ${cells.length}칸 입력`}
        style={{ fontSize: 16, color: theme.color.textMuted, textAlign: "center" }}
      >
        {cells.length > 0 ? `${cellsToUnicode(cells)} · ${cells.length}칸` : "입력 전"}
      </Text>
      <DotPad onCommitCell={(cell) => setCells((prev) => [...prev, cell])} />
      <AppButton label="길게 눌러 제출" requireLongPress onPress={() => void submitAnswer()} onShortPress={readInput} />
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: "center", justifyContent: "center", gap: 16 },
  statusRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  statusItem: { flexDirection: "row", alignItems: "center", gap: 6 },
  chip: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    borderWidth: 2,
    borderRadius: 99,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
});
