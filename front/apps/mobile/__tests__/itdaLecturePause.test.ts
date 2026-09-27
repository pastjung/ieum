import { AppState } from "react-native";
import { pauseItdaForLecture, startItda } from "@/services/itda";

// 잇다 본체는 음성 인식 네이티브 모듈을 쓴다 — 엔진만 가짜로 바꿔 시작·중지 호출을 센다
const mockEngine = { start: jest.fn(async () => undefined), stop: jest.fn(), pause: jest.fn(), isRunning: false };

jest.mock("@/services/itdaVoiceEngine", () => ({
  ItdaVoiceEngine: jest.fn().mockImplementation(() => mockEngine),
}));
jest.mock("@ieum/itda", () => ({
  CapsuleSession: jest.fn().mockImplementation(() => ({ reset: jest.fn(), handle: jest.fn(), pendingQuestion: null })),
  ItdaCapsule: jest.fn(),
  NluPipeline: { create: jest.fn() },
}));
jest.mock("@/services/nlModel", () => ({ nlModelJson: {} }));
jest.mock("@/services/tactileDevice", () => ({ tactileDevice: {} }));
jest.mock("@/a11y", () => ({ speakAsItdaAsync: jest.fn(async () => undefined), stopSpeaking: jest.fn() }));
jest.mock("@/stores/settingsStore", () => ({ useSettingsStore: { getState: () => ({ itdaEnabled: true }) } }));
jest.mock("expo-router", () => ({ router: {} }));

async function flushMicrotasks(times = 5): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

beforeEach(async () => {
  AppState.currentState = "active";
  pauseItdaForLecture(false); // 이전 테스트의 막음을 푼다
  await startItda(); // 잇다 인스턴스를 만들어 둔다 (stopItda는 인스턴스가 있어야 동작한다)
  await flushMicrotasks();
  jest.clearAllMocks();
});

describe("pauseItdaForLecture", () => {
  it("쉬게 하면 잇다를 멈춘다", () => {
    pauseItdaForLecture(true);

    expect(mockEngine.stop).toHaveBeenCalledTimes(1);
  });

  it("쉬는 동안에는 앱 복귀 등으로 startItda가 불려도 시작하지 않는다", async () => {
    pauseItdaForLecture(true);

    await startItda();

    expect(mockEngine.start).not.toHaveBeenCalled();
  });

  it("쉼을 풀면 잇다를 다시 시작한다", async () => {
    pauseItdaForLecture(true);

    pauseItdaForLecture(false);
    await flushMicrotasks();

    expect(mockEngine.start).toHaveBeenCalledTimes(1);
  });

  it("앱이 배경에 있을 때 쉼을 풀어도 시작하지 않는다 (I2)", async () => {
    pauseItdaForLecture(true);
    AppState.currentState = "background";

    pauseItdaForLecture(false);
    await flushMicrotasks();

    expect(mockEngine.start).not.toHaveBeenCalled();
  });

  it("같은 값이 연달아 오면 아무것도 하지 않는다", async () => {
    pauseItdaForLecture(false);
    await flushMicrotasks();
    expect(mockEngine.start).not.toHaveBeenCalled();

    pauseItdaForLecture(true);
    pauseItdaForLecture(true);
    expect(mockEngine.stop).toHaveBeenCalledTimes(1);
  });
});
