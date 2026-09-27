import { useCallback, useEffect, useRef, useState } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { ScreenShell } from "@/components/ScreenShell";
import { BackHeader } from "@/components/BackHeader";
import { AppButton } from "@/components/AppButton";
import { ListRow } from "@/components/ListRow";
import { announce, clonedVoiceSelfTest, hapticError, hapticSuccess } from "@/a11y";
import { clearSynthesisCache, cloneVoice } from "@/services/cartesia";
import { startItda, stopItda } from "@/services/itda";
import { SAMPLE_MAX_MS, VoiceSampleRecorder } from "@/services/voiceSample";
import { useSettingsStore } from "@/stores/settingsStore";
import { useFontScale, useTheme } from "@/theme";

type Phase = "idle" | "recording" | "working";

const MAX_SECONDS = Math.floor(SAMPLE_MAX_MS / 1000);
const READ_ALOUD =
  "저는 점자를 배우고 있습니다. 여섯 개의 점으로 글자를 만들고, 손끝으로 읽습니다.";

/**
 * 내 목소리 복제 — 샘플을 녹음해 서버(백엔드가 Cartesia를 대신 부른다)에 올리고, 그 목소리로 잇다가 답하게 한다.
 *
 * 녹음 중에는 **음성 안내를 하지 않는다.** 안내가 마이크로 다시 들어가면 그 목소리까지
 * 같이 복제되기 때문이다. 그동안의 피드백은 진동과 화면 글씨로만 준다.
 */
export default function VoiceClone() {
  const theme = useTheme();
  const fontScale = useFontScale();
  const clonedVoice = useSettingsStore((s) => s.clonedVoice);
  const setClonedVoice = useSettingsStore((s) => s.setClonedVoice);

  const recorder = useRef(new VoiceSampleRecorder());
  const [phase, setPhase] = useState<Phase>("idle");
  const [seconds, setSeconds] = useState(0);
  const [status, setStatus] = useState("");

  useEffect(() => {
    announce("내 목소리 설정이에요. 녹음 버튼을 누르고 화면의 문장을 읽어 주세요.");

    // 잇다는 대기 중에 마이크를 계속 쥐고 있다. 그대로 두면 녹음이 마이크를 못 얻어
    // AudioRecord가 초기화에 실패한다. 이 화면에 있는 동안만 비켜 준다.
    stopItda();

    const active = recorder.current;
    return () => {
      active.cancel();
      void startItda();
    };
  }, []);

  const finish = useCallback(async () => {
    setPhase("working");
    setStatus("목소리를 만드는 중이에요. 열댓 초 걸려요.");

    const sample = await recorder.current.stop();
    if (!sample) {
      setPhase("idle");
      setStatus("너무 짧아요. 3초보다 길게 읽어 주세요.");
      void hapticError();
      announce("너무 짧아요. 3초보다 길게 읽어 주세요.");
      return;
    }

    try {
      const voice = await cloneVoice(sample.uri);
      clearSynthesisCache();
      setClonedVoice({ id: voice.id, name: voice.name });
      setStatus(`복제했어요. ${sample.seconds}초 샘플로 만들었어요.`);
      void hapticSuccess();
      announce("목소리를 복제했어요. 이제 잇다가 이 목소리로 말해요.");
    } catch (error) {
      const message = reason(error);
      setStatus(`복제하지 못했어요. ${message}`);
      void hapticError();
      announce(`목소리 복제에 실패했어요. ${message}`);
    } finally {
      setPhase("idle");
    }
  }, [setClonedVoice]);

  // 1초마다 진행 시간을 세고, 최대 길이에 닿으면 알아서 끝낸다.
  useEffect(() => {
    if (phase !== "recording") return;
    const timer = setInterval(() => {
      const elapsed = Math.round(recorder.current.elapsedMs / 1000);
      setSeconds(elapsed);
      if (elapsed >= MAX_SECONDS) void finish();
    }, 1000);
    return () => clearInterval(timer);
  }, [phase, finish]);

  const begin = async () => {
    const failure = await recorder.current.start();
    if (failure) {
      setStatus(failure);
      void hapticError();
      announce(failure);
      return;
    }
    setSeconds(0);
    setPhase("recording");
    setStatus("듣고 있어요. 아래 문장을 또박또박 읽어 주세요.");
    void hapticSuccess();
  };

  const test = async () => {
    setPhase("working");
    setStatus("재생을 준비하는 중이에요.");
    setStatus(await clonedVoiceSelfTest());
    setPhase("idle");
  };

  const forget = () => {
    setClonedVoice(null);
    clearSynthesisCache();
    setStatus("복제한 목소리를 지웠어요. 다시 기본 목소리로 안내해요.");
    announce("복제한 목소리를 지웠어요.");
  };

  const busy = phase === "working";
  const recording = phase === "recording";

  return (
    <ScreenShell>
      <BackHeader title="내 목소리" />
      <ScrollView contentContainerStyle={{ gap: 12, paddingBottom: 20 }} showsVerticalScrollIndicator={false}>
        <ListRow
          label="복제한 목소리"
          description="잇다가 이 목소리로 답해요"
          value={clonedVoice ? "있음" : "없음"}
          emphasizeValue={clonedVoice != null}
        />

        <View style={[styles.card, { borderColor: theme.color.border }]}>
          <Text style={{ color: theme.color.textMuted, fontSize: 14 * fontScale, fontWeight: "700" }}>
            읽을 문장
          </Text>
          <Text style={{ color: theme.color.text, fontSize: 18 * fontScale, fontWeight: "700", lineHeight: 28 * fontScale }}>
            {READ_ALOUD}
          </Text>
        </View>

        {recording && (
          <Text
            accessibilityLiveRegion="polite"
            style={{ color: theme.color.primary, fontSize: 22 * fontScale, fontWeight: "900" }}
          >
            {`녹음 중 ${seconds}초 / ${MAX_SECONDS}초`}
          </Text>
        )}

        {status.length > 0 && (
          <Text
            accessibilityLiveRegion="polite"
            style={{ color: theme.color.text, fontSize: 16 * fontScale, fontWeight: "700", lineHeight: 24 * fontScale }}
          >
            {status}
          </Text>
        )}

        <AppButton
          label={recording ? "녹음 끝내고 복제하기" : busy ? "처리 중이에요" : "녹음 시작"}
          disabled={busy}
          accessibilityHint={recording ? "녹음을 끝내고 목소리를 만들어요" : "문장을 읽어 목소리 샘플을 녹음해요"}
          onPress={() => void (recording ? finish() : begin())}
        />
        <AppButton
          label="테스트 재생"
          variant="secondary"
          disabled={busy || recording || clonedVoice == null}
          accessibilityHint="복제한 목소리로 한 문장 읽어 봐요"
          onPress={() => void test()}
        />
        <AppButton
          label="복제한 목소리 지우기"
          variant="secondary"
          disabled={busy || recording || clonedVoice == null}
          requireLongPress
          onShortPress={() => announce("길게 누르면 복제한 목소리를 지워요.")}
          accessibilityHint="길게 누르면 지워요"
          onPress={forget}
        />
      </ScrollView>
    </ScreenShell>
  );
}

function reason(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

const styles = StyleSheet.create({
  card: {
    borderWidth: 2,
    borderRadius: 16,
    padding: 16,
    gap: 8,
  },
});
