import { AudioSession } from "@livekit/react-native";
import { ApiError } from "@ieum/api-client";
import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";

type Listener = (...args: unknown[]) => void;

const mockRooms: MockRoom[] = [];
// 설정되어 있으면 다음 connect()가 이 프라미스를 기다린다(거부되면 connect도 거부된다)
let mockConnectGate: Promise<undefined> | null = null;
let mockConnectReached: (() => void) | null = null;

class MockRoom {
  metadata: string | undefined = undefined;
  listeners = new Map<string, Listener[]>();
  connect = jest.fn(async () => {
    const gate = mockConnectGate;
    mockConnectGate = null;
    mockConnectReached?.();
    if (gate) await gate;
  });
  disconnect = jest.fn(async () => {
    this.emit("disconnected", 1);
  });
  constructor() {
    mockRooms.push(this);
  }
  on(event: string, listener: Listener) {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener]);
    return this;
  }
  removeAllListeners() {
    this.listeners.clear();
    return this;
  }
  emit(event: string, ...args: unknown[]) {
    (this.listeners.get(event) ?? []).forEach((l) => l(...args));
  }
}

jest.mock("livekit-client", () => ({
  Room: jest.fn().mockImplementation(() => new MockRoom()),
  RoomEvent: { RoomMetadataChanged: "roomMetadataChanged", Disconnected: "disconnected" },
}));

// 오디오 세션이 지금 켜져 있는지 — start/stop 호출 순서를 그대로 따라간다
let mockAudioRunning = false;

jest.mock("@livekit/react-native", () => ({
  AudioSession: {
    startAudioSession: jest.fn(async () => {
      mockAudioRunning = true;
    }),
    stopAudioSession: jest.fn(async () => {
      mockAudioRunning = false;
    }),
  },
}));

jest.mock("@/services/classService", () => ({
  classService: { getLecture: jest.fn(), issueMediaToken: jest.fn() },
}));

const mockedClass = classService as unknown as { getLecture: jest.Mock; issueMediaToken: jest.Mock };
const mockedAudio = AudioSession as unknown as { startAudioSession: jest.Mock; stopAudioSession: jest.Mock };

function handlers() {
  return { onOpen: jest.fn(), onExit: jest.fn() };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 대기 중인 마이크로태스크(예: 이미 resolve된 프라미스 체인)를 흘려보낸다 */
async function flushMicrotasks(times = 5): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

beforeEach(async () => {
  await liveLecture.leave();
  mockRooms.length = 0;
  jest.clearAllMocks();
  mockAudioRunning = false;
  mockConnectGate = null;
  mockConnectReached = null;
  mockedClass.issueMediaToken.mockResolvedValue({ serverUrl: "wss://s", token: "t", expiresAt: "x" });
});

describe("liveLecture", () => {
  it("토큰으로 방에 접속한다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const h = handlers();

    await liveLecture.join("42", h);

    expect(mockRooms[0].connect).toHaveBeenCalledWith("wss://s", "t");
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBe("42");
  });

  it("이미 시작된 수업이면 접속 직후 시작을 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();

    await liveLecture.join("42", h);

    expect(h.onOpen).toHaveBeenCalledTimes(1);
  });

  it("방 메타데이터가 OPEN으로 바뀌면 한 번만 시작을 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const h = handlers();
    await liveLecture.join("42", h);

    mockRooms[0].emit("roomMetadataChanged", '{"status":"OPEN"}');
    mockRooms[0].emit("roomMetadataChanged", '{"status":"OPEN"}');

    expect(h.onOpen).toHaveBeenCalledTimes(1);
  });

  it("방이 삭제되면 수업 종료를 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();
    await liveLecture.join("42", h);

    mockRooms[0].emit("disconnected", 5);

    expect(h.onExit).toHaveBeenCalledWith("ended");
    expect(liveLecture.currentLectureId()).toBeNull();
  });

  it("그 밖의 해제는 연결 끊김으로 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();
    await liveLecture.join("42", h);

    mockRooms[0].emit("disconnected", 2);

    expect(h.onExit).toHaveBeenCalledWith("lost");
  });

  it("사용자가 나가면 퇴장으로 한 번만 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();
    await liveLecture.join("42", h);

    await liveLecture.leave();

    expect(h.onExit).toHaveBeenCalledTimes(1);
    expect(h.onExit).toHaveBeenCalledWith("left");
  });

  it("다시 입장하면 이전 방을 끊되 이전 핸들러는 부르지 않는다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const first = handlers();
    await liveLecture.join("42", first);

    await liveLecture.join("42", handlers());

    expect(mockRooms[0].disconnect).toHaveBeenCalled();
    expect(first.onExit).not.toHaveBeenCalled();
  });

  it("토큰 발급이 실패하면 방에 접속하지 않고 오류를 던진다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    mockedClass.issueMediaToken.mockRejectedValue(new Error("503"));

    await expect(liveLecture.join("42", handlers())).rejects.toThrow("503");
    expect(mockRooms).toHaveLength(0);
    expect(liveLecture.currentLectureId()).toBeNull();
  });

  it("겹쳐서 입장하면 나중 요청만 방에 남는다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const tokenGate = deferred<{ serverUrl: string; token: string; expiresAt: string }>();
    mockedClass.issueMediaToken
      .mockImplementationOnce(() => tokenGate.promise)
      .mockImplementationOnce(async () => ({ serverUrl: "wss://s2", token: "t2", expiresAt: "x" }));

    const first = handlers();
    const second = handlers();

    const joinFirst = liveLecture.join("42", first);
    // 첫 번째 요청이 getLecture를 지나 토큰 발급 대기 상태(tokenGate)에 들어가게 한다
    await flushMicrotasks();
    // 두 번째 입장은 토큰이 바로 도착해 먼저 끝난다
    await liveLecture.join("42", second);

    expect(mockRooms).toHaveLength(1);
    expect(mockRooms[0].connect).toHaveBeenCalledWith("wss://s2", "t2");
    expect(liveLecture.currentLectureId()).toBe("42");

    // 이제서야 첫 번째 토큰이 도착한다 — 이미 낡은 요청이니 방을 만들지 않는다
    tokenGate.resolve({ serverUrl: "wss://s1", token: "t1", expiresAt: "x" });
    await joinFirst;

    expect(mockRooms).toHaveLength(1);
    expect(first.onOpen).not.toHaveBeenCalled();
    expect(first.onExit).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBe("42");
  });

  it("입장 대기 중 나가면 그 이후 응답이 와도 방에 연결하지 않는다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const tokenGate = deferred<{ serverUrl: string; token: string; expiresAt: string }>();
    const reachedTokenGate = deferred<void>();
    mockedClass.issueMediaToken.mockImplementationOnce(() => {
      reachedTokenGate.resolve(); // 토큰 발급 호출까지 실제로 도달했다는 신호 — 큐에 낡은 구현이 남지 않게 한다
      return tokenGate.promise;
    });

    const h = handlers();
    const joinPromise = liveLecture.join("42", h);
    await reachedTokenGate.promise; // 토큰 발급이 대기 중인 상태가 되도록 보장한다

    await liveLecture.leave();

    tokenGate.resolve({ serverUrl: "wss://s", token: "t", expiresAt: "x" });
    await joinPromise;

    expect(mockRooms).toHaveLength(0);
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(h.onExit).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBeNull();
  });

  it("먼저 시작한 요청의 오디오 세션이 늦게 열려도 이미 연결된 세션의 오디오를 끄지 않는다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    mockedClass.issueMediaToken
      .mockResolvedValueOnce({ serverUrl: "wss://a", token: "ta", expiresAt: "x" })
      .mockResolvedValueOnce({ serverUrl: "wss://b", token: "tb", expiresAt: "x" });

    const audioGateA = deferred<void>();
    const reachedAudioGateA = deferred<void>();
    mockedAudio.startAudioSession
      .mockImplementationOnce(() => {
        reachedAudioGateA.resolve(); // A가 startAudioSession을 호출했다는 신호 — 정확한 시점을 타이밍 추측 없이 보장한다
        return audioGateA.promise;
      })
      .mockImplementationOnce(async () => undefined); // B는 바로 열린다

    const a = handlers();
    const b = handlers();

    const joinA = liveLecture.join("42", a);
    // A가 getLecture·issueMediaToken을 지나 startAudioSession 대기 상태(audioGateA)에 들어갈 때까지 기다린다
    await reachedAudioGateA.promise;

    // B는 완전히 접속을 마친다 — 오디오 세션도 이미 열었다
    await liveLecture.join("42", b);

    expect(mockRooms).toHaveLength(1);
    expect(mockRooms[0].connect).toHaveBeenCalledWith("wss://b", "tb");
    expect(mockedAudio.stopAudioSession).not.toHaveBeenCalled();

    // 이제서야 A의 오디오 세션이 열린다 — 이미 낡은 요청이니 B의 오디오를 꺼서는 안 된다
    audioGateA.resolve();
    await joinA;

    expect(mockedAudio.stopAudioSession).not.toHaveBeenCalled();
    expect(mockRooms).toHaveLength(1); // A는 방을 만들지 않는다
    expect(mockRooms[0].disconnect).not.toHaveBeenCalled(); // B는 여전히 연결되어 있다
    expect(a.onOpen).not.toHaveBeenCalled();
    expect(a.onExit).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBe("42");
  });

  it("이미 끝난 수업이면 토큰을 받지 않고 수업 종료로 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "CLOSED" });
    const h = handlers();

    await liveLecture.join("42", h);

    expect(h.onExit).toHaveBeenCalledTimes(1);
    expect(h.onExit).toHaveBeenCalledWith("ended");
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(mockedClass.issueMediaToken).not.toHaveBeenCalled();
    expect(mockRooms).toHaveLength(0);
    expect(mockedAudio.startAudioSession).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBeNull();
  });

  it("토큰 발급이 수업 상태 오류(409)면 수업 종료로 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    mockedClass.issueMediaToken.mockRejectedValue(new ApiError(409, "INVALID_LECTURE_STATE", "종료된 수업"));
    const h = handlers();

    await liveLecture.join("42", h);

    expect(h.onExit).toHaveBeenCalledTimes(1);
    expect(h.onExit).toHaveBeenCalledWith("ended");
    expect(mockRooms).toHaveLength(0);
    expect(mockedAudio.startAudioSession).not.toHaveBeenCalled();
  });

  it("참여 기록이 없어 토큰이 거부(403)되면 그 오류를 던진다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const denied = new ApiError(403, "LECTURE_PARTICIPATION_REQUIRED", "참여 필요");
    mockedClass.issueMediaToken.mockRejectedValue(denied);
    const h = handlers();

    await expect(liveLecture.join("42", h)).rejects.toBe(denied);
    expect(h.onExit).not.toHaveBeenCalled();
    expect(mockRooms).toHaveLength(0);
  });

  it("연결된 방을 두고 다시 입장하다 토큰에서 실패하면 오디오 세션이 남지 않는다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    await liveLecture.join("42", handlers());
    expect(mockAudioRunning).toBe(true);

    mockedClass.issueMediaToken.mockRejectedValueOnce(new Error("503"));
    await expect(liveLecture.join("42", handlers())).rejects.toThrow("503");

    expect(mockRooms[0].disconnect).toHaveBeenCalled();
    expect(mockAudioRunning).toBe(false);
    expect(liveLecture.currentLectureId()).toBeNull();
  });

  it("방 접속 중에 나가면 접속 실패를 던지지 않고 조용히 끝난다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const connectGate = deferred<undefined>();
    const reachedConnect = deferred<void>();
    mockConnectGate = connectGate.promise;
    mockConnectReached = () => reachedConnect.resolve();
    const h = handlers();

    const joinPromise = liveLecture.join("42", h);
    // join이 방을 만들고 connect 대기에 들어갈 때까지 기다린다
    await reachedConnect.promise;

    // 실제 LiveKit은 접속 중에 disconnect()하면 connect()가 거부된다
    const leaving = liveLecture.leave();
    connectGate.reject(new Error("Client initiated disconnect"));
    await leaving;

    await expect(joinPromise).resolves.toBeUndefined();
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(mockAudioRunning).toBe(false);
    expect(liveLecture.currentLectureId()).toBeNull();
  });
});
