import { speakAsync } from "@/a11y/speech";

// jest.mock 은 호이스팅되므로 팩토리가 참조하는 변수는 이름이 `mock`으로 시작해야 한다
const mockSpeak = jest.fn();
const mockStop = jest.fn();
const mockSettings = { voiceEnabled: true, speechRate: "normal" as const };

jest.mock("expo-speech", () => ({
  speak: (...args: unknown[]) => mockSpeak(...args),
  stop: (...args: unknown[]) => mockStop(...args),
}));

// 복제 음성은 speakAsItdaAsync 만 쓴다. 여기서는 import 사슬(네트워크·파일)만 끊어 둔다
jest.mock("@/a11y/clonedVoice", () => ({
  canUseClonedVoice: () => false,
  speakWithClonedVoice: jest.fn(),
  stopClonedVoice: jest.fn(),
}));

jest.mock("@/stores/settingsStore", () => ({
  useSettingsStore: { getState: () => mockSettings },
  speechRateValue: () => 1,
}));

interface SpeakOptions {
  onDone?: () => void;
  onStopped?: () => void;
  onError?: () => void;
}

/** `Speech.speak`에 넘어간 콜백 묶음 */
function optionsOf(call: unknown[]): SpeakOptions {
  return call[1] as SpeakOptions;
}

/**
 * promise가 풀렸는지, 아니면 갇혔는지 본다.
 *
 * 그냥 `await` 하면 갇혔을 때 테스트가 jest 타임아웃으로 죽어 "무엇이 잘못됐는지"가
 * 남지 않는다. 넉넉한 상한을 두고 경주시켜 둘을 구별한다.
 */
async function settleOrStuck(promise: Promise<void>): Promise<"spoken" | "stuck"> {
  const race = Promise.race([
    promise.then(() => "spoken" as const),
    new Promise<"stuck">((resolve) => setTimeout(() => resolve("stuck"), 60_000)),
  ]);
  await jest.advanceTimersByTimeAsync(60_000);
  return race;
}

describe("speakAsync — 낭독이 끝나면 resolve된다", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    mockSpeak.mockReset();
    mockStop.mockReset();
    mockSettings.voiceEnabled = true;
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("TTS 콜백이 하나도 오지 않아도 결국 풀린다", async () => {
    // 이 앱의 자동 화면 전환은 이 promise를 기다린다. 스플래시(`src/app/index.tsx`)는
    // `Promise.all([speakAsync(...), minWait])` 뒤에 `router.replace`를 부른다.
    //
    // 안드로이드 에뮬레이터에서 Google TTS 서비스가 한국어 합성 도중 죽는 일이 실제로 있다
    // (logcat: "Scheduling restart of crashed service com.google.android.tts"). 그러면
    // expo-speech 는 onDone·onStopped·onError 중 무엇도 부르지 않는다 — 콜백에만 기대면
    // promise 가 영영 settle 되지 않고, 앱은 **로고 화면에서 넘어가지 못한 채 멈춘다.**
    // 아무것도 던지지 않으므로 오류 로그조차 남지 않는다.
    mockSpeak.mockImplementation(() => {
      // 콜백을 부르지 않는다 — 엔진이 죽은 상황을 그대로 흉내 낸다
    });

    expect(await settleOrStuck(speakAsync("점자를 잇다, 이음"))).toBe("spoken");
  });

  it("콜백이 오면 상한을 기다리지 않고 바로 풀린다", async () => {
    // 가드가 낭독을 잘라먹거나 전환을 늦추면 안 된다
    mockSpeak.mockImplementation(() => {
      optionsOf(mockSpeak.mock.calls[0]!).onDone?.();
    });

    const promise = speakAsync("안내");
    await jest.advanceTimersByTimeAsync(0);

    await expect(promise).resolves.toBeUndefined();
  });

  it("낭독이 실패해도 풀린다", async () => {
    mockSpeak.mockImplementation(() => {
      optionsOf(mockSpeak.mock.calls[0]!).onError?.();
    });

    expect(await settleOrStuck(speakAsync("안내"))).toBe("spoken");
  });

  it("음성을 꺼 두면 TTS를 부르지 않고 바로 풀린다", async () => {
    mockSettings.voiceEnabled = false;

    await expect(speakAsync("안내")).resolves.toBeUndefined();
    expect(mockSpeak).not.toHaveBeenCalled();
  });
});
