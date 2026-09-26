import { router } from "expo-router";
import { AppState } from "react-native";
import {
  CapsuleSession,
  ItdaCapsule,
  NluPipeline,
  type CapsuleResult,
  type Navigation,
} from "@ieum/itda";
import { speakAsItdaAsync, stopSpeaking } from "@/a11y";
import { useItdaStore } from "@/stores/itdaStore";
import { useSettingsStore } from "@/stores/settingsStore";
import { ItdaVoiceEngine, type ItdaVoiceListener } from "./itdaVoiceEngine";
import { nlModelJson } from "./nlModel";
import { tactileDevice } from "./tactileDevice";

/**
 * 잇다 음성 비서 본체 — 들은 말을 해석해 실행하고, 답을 읽어 준다.
 *
 * ```
 * ASR(ItdaVoiceEngine) → NLU(@ieum/itda) → 캡슐 → 이동(expo-router) + 음성(speakAsItda) + 모듈(HC-06)
 * ```
 *
 * 판단은 전부 `@ieum/itda`(순수 로직)에 있고, 여기서는 **부수효과만** 수행한다.
 */
class ItdaAssistant implements ItdaVoiceListener {
  private readonly engine = new ItdaVoiceEngine(this);
  private readonly pipeline = NluPipeline.create(nlModelJson);
  private readonly session = new CapsuleSession(
    () => this.pipeline,
    new ItdaCapsule({ tactile: tactileDevice }),
  );

  /** 못 알아들었을 때 한 번만 되묻는다. */
  private retried = false;

  get isRunning(): boolean {
    return this.engine.isRunning;
  }

  async start(): Promise<void> {
    await this.engine.start();
  }

  stop(): void {
    this.engine.stop();
    this.session.reset();
    stopSpeaking();
    useItdaStore.getState().reset();
  }

  /** 음성이 아닌 경로(테스트·바로 실행)로 들어온 발화도 같은 길을 탄다. */
  async handle(utterance: string): Promise<void> {
    const store = useItdaStore.getState();
    store.set({ phase: "thinking", partial: "", lastHeard: utterance });

    const { result } = await this.session.handle(utterance);
    store.set({
      speech: result.speech,
      pendingQuestion: this.session.pendingQuestion,
    });
    this.apply(result);
  }

  // region ItdaVoiceEngine.Listener

  onPhase(phase: Parameters<ItdaVoiceListener["onPhase"]>[0]): void {
    useItdaStore.getState().set({ phase });
  }

  onPartial(text: string): void {
    useItdaStore.getState().set({ partial: text });
  }

  onWake(command: string): void {
    this.retried = false;
    if (command.length === 0) {
      useItdaStore.getState().set({ partial: "", lastHeard: "하이 잇다" });
      this.say("네, 잇다예요. 무엇을 도와드릴까요?", () => this.engine.startCommandListening());
      return;
    }
    // "하이 잇다, 3번 점 올려줘"처럼 한 번에 말한 경우
    void this.handle(command);
  }

  onCommand(text: string): void {
    void this.handle(text);
  }

  onCommandMiss(): void {
    if (!this.retried) {
      this.retried = true;
      this.say("잘 못 들었어요. 한 번만 더 말씀해 주세요.", () => this.engine.startCommandListening());
      return;
    }
    this.retried = false;
    this.session.reset();
    this.say("다음에 다시 불러 주세요.", () => this.engine.startWakeListening());
  }

  onNotice(message: string): void {
    useItdaStore.getState().set({ notice: message });
  }

  // endregion

  private apply(result: CapsuleResult): void {
    switch (result.kind) {
      case "done":
        this.retried = false;
        this.navigate(result.navigation);
        this.say(result.speech, () => this.engine.startWakeListening());
        return;

      case "needsConcept":
        this.say(result.speech, () => this.engine.startCommandListening());
        return;

      case "notUnderstood":
        if (!this.retried) {
          this.retried = true;
          this.say(result.speech, () => this.engine.startCommandListening());
        } else {
          this.retried = false;
          this.say(result.speech, () => this.engine.startWakeListening());
        }
    }
  }

  private navigate(navigation: Navigation): void {
    if (navigation.kind === "stay") return;
    if (navigation.kind === "back") {
      if (router.canGoBack()) router.back();
      return;
    }
    // 탭 화면은 push 하면 같은 탭 위에 겹쳐 쌓이거나(학습·설정), 홈 `/(tabs)`처럼 아예 움직이지 않는다.
    // 이미 있는 화면으로 "옮겨 가는" navigate 를 쓴다. 잇다가 만든 경로만 넘기므로 타입 단언이 안전하다.
    router.navigate(navigation.route as Parameters<typeof router.navigate>[0]);
  }

  /**
   * 말하고 나서 다음 단계로 넘어간다.
   *
   * **낭독이 실제로 끝난 뒤에** 마이크를 연다. 길이를 글자 수로 어림잡던 때는 안내가 끝나고도
   * 한참 뒤에 열려서, 바로 이어 말한 명령의 앞부분을 통째로 놓쳤다.
   * 말하는 동안 마이크를 닫아 두는 것은 그대로다 — 자기 목소리를 명령으로 되듣지 않기 위해.
   */
  private say(text: string, next: () => void): void {
    this.engine.pause();
    useItdaStore.getState().set({ phase: "speaking", speech: text });

    void speakAsItdaAsync(text).finally(() => {
      if (this.engine.isRunning) next();
    });
  }
}

let assistant: ItdaAssistant | null = null;

function instance(): ItdaAssistant {
  assistant ??= new ItdaAssistant();
  return assistant;
}

/**
 * 수업에서 내 마이크가 켜져 있는 동안 잇다는 쉰다 — 한 마이크를 두고 음성 인식과 수업 송출이 다투지 않게.
 * 앱이 앞으로 돌아올 때 `_layout`이 부르는 startItda도 이 동안에는 무시한다.
 */
let pausedForLecture = false;

/**
 * 설정에서 켠 상태일 때만 시작한다.
 *
 * 여기서 터지면 **앱이 켜질 때마다 같은 자리에서 죽는다** — 설정이 저장돼 있어서 다음 실행에도
 * 그대로 들어오기 때문이다. 실패는 화면에 남기고 앱은 계속 가게 둔다.
 */
export async function startItda(): Promise<void> {
  if (pausedForLecture) return;
  if (!useSettingsStore.getState().itdaEnabled) return;
  try {
    await instance().start();
  } catch (error) {
    useItdaStore.getState().set({
      phase: "error",
      notice: `잇다를 시작하지 못했어요: ${error instanceof Error ? error.message : String(error)}`,
    });
  }
}

/** 아직 한 번도 켠 적이 없으면 만들지도 않는다 — 모델 파싱 비용을 앱 시작에서 뺀다. */
export function stopItda(): void {
  assistant?.stop();
}

export function isItdaRunning(): boolean {
  return assistant?.isRunning ?? false;
}

/** 음성 없이 같은 경로로 실행한다. */
export function handleItdaUtterance(utterance: string): Promise<void> {
  return instance().handle(utterance);
}

/** 수업 마이크를 켤 때 true, 끄거나 발언이 회수되거나 방을 떠날 때 false. 같은 값이 연달아 오면 무시한다 */
export function pauseItdaForLecture(paused: boolean): void {
  if (pausedForLecture === paused) return;
  pausedForLecture = paused;
  if (paused) stopItda();
  // 앱이 배경에 있는 동안 풀렸다면(네트워크 끊김·회수·수업 종료가 배경에서 일어난 경우) 여기서
  // 시작하지 않는다 — `_layout`의 포그라운드 전환이 이미 pausedForLecture=false를 보고
  // 자연히 startItda를 부른다. 배경에서 시작하면 인식이 무음이거나 오류 상태로 끝난다
  else if (AppState.currentState === "active") void startItda();
}
