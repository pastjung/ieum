import { ExpoSpeechRecognitionModule } from "expo-speech-recognition";
import type { EventSubscription } from "expo-modules-core";
import { detectWakeWord } from "@ieum/itda";

/**
 * 호출어 감시 + 명령 받아쓰기 상태 기계.
 *
 * 안드로이드 인식기는 한 번에 한 세션만 돈다. 그래서 **세션을 다시 여는 결정은 `end`
 * 한 곳에서만** 한다. `abort()`가 `error("aborted")`와 `end`를 둘 다 쏘기 때문에,
 * 두 곳에서 재시작을 걸면 abort → start → abort가 끝없이 돌아 인식기가 말을 들을 틈이 없다.
 *
 * **앱이 화면에 떠 있는 동안만 동작한다** — 화면이 꺼진 상태의 상시 대기는 포그라운드 서비스가
 * 필요한데, 지금 범위가 아니다.
 */
export type VoicePhase = "stopped" | "wake" | "command" | "thinking" | "speaking" | "error";

export interface ItdaVoiceListener {
  onPhase(phase: VoicePhase): void;
  onPartial(text: string): void;
  /** 호출어를 들었다. `command`는 뒤에 이어 말한 명령(없으면 빈 문자열). */
  onWake(command: string): void;
  /** 명령 한 마디를 받아썼다. */
  onCommand(text: string): void;
  /** 명령 모드에서 아무 말도 못 알아들었다. */
  onCommandMiss(): void;
  onNotice(message: string): void;
}

const LANGUAGE = "ko-KR";
const COMMAND_TIMEOUT_MS = 8_000;
/** 호출어만 들렸을 때, 뒤에 명령이 더 붙는지 잠깐 기다리는 시간. */
const WAKE_CONFIRM_DELAY_MS = 700;
/** 인식기가 자원을 놓을 틈. 너무 짧으면 다음 start가 실패한다. */
const RESTART_DELAY_MS = 250;

/** 사람이 말하지 않아서 나는 오류들 — 정상 흐름이라 알리지 않는다. */
const QUIET_ERRORS = new Set(["no-speech", "speech-timeout", "no-match"]);

export class ItdaVoiceEngine {
  private mode: "idle" | "wake" | "command" | "paused" = "idle";
  private running = false;
  /** start()가 진행 중이다 — 겹쳐 들어오는 호출을 막는다. */
  private starting = false;
  /** 인식 세션이 살아 있는가 (start ~ end 사이). */
  private listening = false;
  /** 지금 세션이 끝나면 곧바로 새 세션을 연다. */
  private restartWanted = false;
  /**
   * 명령 모드에서 마지막으로 들은 중간 결과.
   *
   * 안드로이드 인식기는 짧은 발화에서 **최종 결과 없이 세션을 끝내는 일이 잦다.**
   * 중간 결과까지 버리면 다 듣고도 못 알아들은 것이 되므로 붙잡아 둔다.
   */
  private commandPartial = "";
  /** stop()이 끼어들었는지 가리는 표식 — 기다리는 사이에 꺼진 start를 되살리지 않는다. */
  private generation = 0;
  private subscriptions: EventSubscription[] = [];
  private wakeTimer: ReturnType<typeof setTimeout> | null = null;
  private commandTimer: ReturnType<typeof setTimeout> | null = null;
  private restartTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly listener: ItdaVoiceListener) {}

  get isRunning(): boolean {
    return this.running;
  }

  async start(): Promise<void> {
    if (this.running || this.starting) return;

    this.starting = true;
    const generation = this.generation;
    try {
      if (!(await this.ensurePermission())) {
        this.listener.onNotice("마이크와 음성 인식 권한이 필요해요. 설정에서 허용해 주세요.");
        this.listener.onPhase("error");
        return;
      }

      // 권한을 기다리는 사이 앱이 뒤로 가서 stop()이 불렸을 수 있다. 그때는 되살리지 않는다.
      if (generation !== this.generation) return;

      this.attach();
      this.running = true;
      this.startWakeListening();
    } finally {
      this.starting = false;
    }
  }

  /**
   * **이미 허용돼 있으면 묻지 않는다.**
   *
   * `requestPermissionsAsync()`는 허용된 상태에서도 권한 액티비티를 띄우는데, 그 사이 앱이
   * 잠깐 백그라운드로 내려간다. 포그라운드 복귀를 보고 다시 시작을 거는 쪽과 맞물리면
   * 요청 → 백그라운드 → 복귀 → 요청이 끝없이 돈다.
   */
  private async ensurePermission(): Promise<boolean> {
    const current = await ExpoSpeechRecognitionModule.getPermissionsAsync();
    if (current.granted) return true;

    const asked = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
    return asked.granted;
  }

  stop(): void {
    this.generation += 1;
    this.commandPartial = "";
    this.running = false;
    this.mode = "idle";
    this.restartWanted = false;
    this.clearTimers();
    this.safeAbort();
    this.listening = false;
    this.detach();
    this.listener.onPhase("stopped");
  }

  /** 호출어 대기 모드로. */
  startWakeListening(): void {
    if (!this.running) return;
    this.mode = "wake";
    this.listener.onPhase("wake");
    this.openSession();
  }

  /** 명령 받아쓰기 모드로. */
  startCommandListening(): void {
    if (!this.running) return;
    this.mode = "command";
    this.commandPartial = "";
    this.listener.onPhase("command");
    this.openSession();

    this.clearTimer("command");
    this.commandTimer = setTimeout(() => {
      this.commandTimer = null;
      if (this.running && this.mode === "command") this.finishCommand();
    }, COMMAND_TIMEOUT_MS);
  }

  /** 잇다가 말하는 동안에는 마이크를 닫아 자기 목소리를 듣지 않게 한다. */
  pause(): void {
    this.mode = "paused";
    this.restartWanted = false;
    this.clearTimers();
    this.safeAbort();
  }

  // region 인식기

  private attach(): void {
    this.subscriptions = [
      ExpoSpeechRecognitionModule.addListener("result", (event) => {
        const candidates = event.results.map((result) => result.transcript).filter(Boolean);
        if (candidates.length === 0) return;

        if (event.isFinal) this.handleFinal(candidates);
        else this.handlePartial(candidates);
      }),

      // 세션이 끝났다. **새 세션을 여는 유일한 자리다.**
      ExpoSpeechRecognitionModule.addListener("end", () => {
        this.listening = false;
        if (!this.running) return;

        // 명령 세션이 끝났다면 8초 시간 초과를 기다릴 이유가 없다 — 들은 것으로 바로 판단한다.
        if (this.mode === "command") {
          this.finishCommand();
          return;
        }

        if (this.restartWanted) {
          this.restartWanted = false;
          this.openSession();
          return;
        }
        // 스스로 끝난 대기 세션은 이어서 다시 연다 — 계속 듣는 것처럼 보이게.
        if (this.mode === "wake") this.openSession();
      }),

      ExpoSpeechRecognitionModule.addListener("error", (event) => {
        // "aborted"는 우리가 멈출 때 나는 것이다. 뒤따라 오는 end가 알아서 처리한다.
        if (!this.running || event.error === "aborted") return;

        if (this.mode === "command") {
          this.finishCommand();
          return;
        }
        if (!QUIET_ERRORS.has(event.error)) {
          this.listener.onNotice(`음성 인식 오류: ${event.message || event.error}`);
        }
        // 재시작은 end 핸들러가 한다. 여기서 또 걸면 무한 루프가 된다.
      }),
    ];
  }

  private detach(): void {
    for (const subscription of this.subscriptions) subscription.remove();
    this.subscriptions = [];
  }

  private handlePartial(candidates: string[]): void {
    this.listener.onPartial(candidates[0]);
    if (this.mode === "command") {
      this.commandPartial = candidates[0] ?? this.commandPartial;
      return;
    }
    if (this.mode !== "wake") return;

    const wake = detectWakeWord(candidates);
    if (!wake) return;

    if (wake.command.length === 0) {
      // 호출어만 말한 것 같다. 최종 결과를 기다리지 않고 조금 뒤 바로 반응한다.
      this.scheduleWake("");
    } else {
      // 뒤에 명령이 붙는 중이다. 최종 결과를 받아 한 번에 처리한다.
      this.clearTimer("wake");
    }
  }

  private handleFinal(candidates: string[]): void {
    if (this.mode === "wake") {
      const wake = detectWakeWord(candidates);
      if (wake) this.triggerWake(wake.command);
      // 호출어가 없으면 아무것도 하지 않는다. 세션이 끝나면 end가 다시 열어 준다.
      return;
    }

    if (this.mode === "command") {
      this.commandPartial = candidates[0] ?? this.commandPartial;
      this.finishCommand();
    }
  }

  /**
   * 명령 세션을 마무리한다.
   *
   * 최종 결과가 없어도 **중간 결과가 있으면 그걸 명령으로 쓴다.** 인식기가 최종을
   * 안 주고 끝내는 경우가 많아서, 이걸 안 하면 멀쩡히 들은 말을 통째로 버리게 된다.
   */
  private finishCommand(): void {
    const heard = this.commandPartial.trim();
    this.commandPartial = "";
    this.clearTimers();
    this.pause();

    if (heard.length > 0) this.listener.onCommand(heard);
    else this.listener.onCommandMiss();
  }

  private scheduleWake(command: string): void {
    if (this.wakeTimer) return;
    this.wakeTimer = setTimeout(() => {
      this.wakeTimer = null;
      if (this.running && this.mode === "wake") this.triggerWake(command);
    }, WAKE_CONFIRM_DELAY_MS);
  }

  private triggerWake(command: string): void {
    this.clearTimers();
    this.pause();
    this.listener.onWake(command);
  }

  /**
   * 새 인식 세션을 연다.
   *
   * 이미 돌고 있으면 **멈추라고만 하고 물러난다** — 실제 시작은 `end`가 받아서 한다.
   * 이 규칙 덕분에 start와 abort가 겹치지 않는다.
   */
  private openSession(): void {
    if (!this.running || !this.wantsMicrophone()) return;

    if (this.listening) {
      this.restartWanted = true;
      this.safeAbort();
      return;
    }

    this.clearTimer("restart");
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null;
      if (!this.running || this.listening || !this.wantsMicrophone()) return;

      try {
        this.listening = true;
        ExpoSpeechRecognitionModule.start({
          lang: LANGUAGE,
          interimResults: true,
          continuous: this.mode === "wake",
          maxAlternatives: 3,
          androidIntentOptions: {
            EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS: this.mode === "wake" ? 800 : 1500,
          },
        });
      } catch (error) {
        this.listening = false;
        this.listener.onNotice(
          `음성 인식을 시작하지 못했어요: ${error instanceof Error ? error.message : String(error)}`,
        );
      }
    }, RESTART_DELAY_MS);
  }

  private wantsMicrophone(): boolean {
    return this.mode === "wake" || this.mode === "command";
  }

  /** 돌고 있을 때만 부른다 — 아니면 쓸데없는 aborted 이벤트만 만든다. */
  private safeAbort(): void {
    if (!this.listening) return;
    try {
      ExpoSpeechRecognitionModule.abort();
    } catch {
      // 이미 멈춰 있으면 무시한다.
      this.listening = false;
    }
  }

  private clearTimer(which: "wake" | "command" | "restart"): void {
    const timer =
      which === "wake" ? this.wakeTimer : which === "command" ? this.commandTimer : this.restartTimer;
    if (timer) clearTimeout(timer);
    if (which === "wake") this.wakeTimer = null;
    else if (which === "command") this.commandTimer = null;
    else this.restartTimer = null;
  }

  private clearTimers(): void {
    this.clearTimer("wake");
    this.clearTimer("command");
    this.clearTimer("restart");
  }

  // endregion
}
