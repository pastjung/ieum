import { createAudioPlayer, type AudioPlayer } from "expo-audio";
import { isCartesiaConfigured, synthesizeToFile } from "@/services/cartesia";
import { useSettingsStore } from "@/stores/settingsStore";

/**
 * 복제한 내 목소리로 읽어 주는 경로.
 *
 * `speak()`가 먼저 이걸 시도하고, 안 되면 시스템 TTS로 넘어간다.
 * **폴백은 반드시 일어나야 한다** — 네트워크가 느리거나 끊겨도 안내가 멈추면 안 된다.
 *
 * 폴백이 조용히 일어나면 "왜 내 목소리가 아니지"를 알 수 없으므로 [lastOutcome]에 이유를 남긴다.
 */
export interface SpeechOutcome {
  readonly clonedVoice: boolean;
  readonly detail: string;
}

/** 재생 완료 신호가 오지 않아도 이보다 오래 기다리지 않는다. */
const MAX_PLAYBACK_MS = 15_000;

let outcome: SpeechOutcome | null = null;
let player: AudioPlayer | null = null;

export function lastOutcome(): SpeechOutcome | null {
  return outcome;
}

export function canUseClonedVoice(): boolean {
  const { clonedVoice } = useSettingsStore.getState();
  return isCartesiaConfigured() && clonedVoice !== null;
}

/**
 * 복제 목소리로 읽어 본다.
 * @returns 성공하면 true. false면 부르는 쪽이 시스템 TTS로 읽어야 한다.
 */
export async function speakWithClonedVoice(text: string): Promise<boolean> {
  const { clonedVoice } = useSettingsStore.getState();

  if (!isCartesiaConfigured()) {
    outcome = { clonedVoice: false, detail: "Cartesia API 키가 없어요" };
    return false;
  }
  if (!clonedVoice) {
    outcome = { clonedVoice: false, detail: "복제한 목소리가 없어요" };
    return false;
  }

  try {
    const uri = await synthesizeToFile(clonedVoice.id, text);
    stopClonedVoice();
    const active = createAudioPlayer({ uri });
    player = active;
    outcome = { clonedVoice: true, detail: "복제한 내 목소리로 재생" };
    await playToEnd(active);
    return true;
  } catch (error) {
    outcome = {
      clonedVoice: false,
      detail: `합성 실패: ${error instanceof Error ? error.message : String(error)}`,
    };
    return false;
  }
}

/**
 * 재생이 실제로 끝날 때까지 기다린다.
 *
 * 잇다는 말을 마친 **뒤에** 마이크를 열어야 한다. 길이를 글자 수로 어림잡으면
 * 너무 일찍 열려 자기 목소리를 듣거나, 너무 늦게 열려 사용자 말을 놓친다.
 * 안전장치로 상한을 둬서, 상태 이벤트가 오지 않아도 멈춰 있지 않게 한다.
 */
function playToEnd(active: AudioPlayer): Promise<void> {
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      subscription.remove();
      clearTimeout(guard);
      resolve();
    };

    const subscription = active.addListener("playbackStatusUpdate", (status) => {
      if (status.didJustFinish) finish();
    });
    const guard = setTimeout(finish, MAX_PLAYBACK_MS);

    active.play();
  });
}

export function stopClonedVoice(): void {
  if (!player) return;
  try {
    player.pause();
    player.remove();
  } finally {
    player = null;
  }
}

/**
 * 진단 — 폴백 없이 단계를 밟고 결과를 사람이 읽을 문장으로 돌려준다.
 * "복제했는데 기본 목소리가 난다"의 원인이 키·설정·합성 중 어디인지 가른다.
 */
export async function selfTest(
  text = "안녕하세요, 잇다예요. 이제 이 목소리로 안내해 드릴게요.",
): Promise<string> {
  const { clonedVoice } = useSettingsStore.getState();
  if (!isCartesiaConfigured()) return "실패 ① API 키가 비어 있어요 (.env 확인 후 다시 빌드)";
  if (!clonedVoice) return "실패 ② 복제한 목소리가 저장돼 있지 않아요";

  const startedAt = Date.now();
  try {
    const uri = await synthesizeToFile(clonedVoice.id, text);
    const elapsed = Date.now() - startedAt;
    stopClonedVoice();
    const active = createAudioPlayer({ uri });
    player = active;
    outcome = { clonedVoice: true, detail: "진단 재생" };
    await playToEnd(active);
    return `성공 — voice ${clonedVoice.id.slice(0, 8)} · ${elapsed}ms`;
  } catch (error) {
    return `실패 ③ ${error instanceof Error ? error.message : String(error)}`;
  }
}
