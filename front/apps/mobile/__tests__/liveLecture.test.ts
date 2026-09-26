import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";

type Listener = (...args: unknown[]) => void;

const mockRooms: MockRoom[] = [];

class MockRoom {
  metadata: string | undefined = undefined;
  listeners = new Map<string, Listener[]>();
  connect = jest.fn(async () => undefined);
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

jest.mock("@livekit/react-native", () => ({
  AudioSession: { startAudioSession: jest.fn(async () => undefined), stopAudioSession: jest.fn(async () => undefined) },
}));

jest.mock("@/services/classService", () => ({
  classService: { getLecture: jest.fn(), issueMediaToken: jest.fn() },
}));

const mockedClass = classService as unknown as { getLecture: jest.Mock; issueMediaToken: jest.Mock };

function handlers() {
  return { onOpen: jest.fn(), onExit: jest.fn() };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/** 대기 중인 마이크로태스크(예: 이미 resolve된 프라미스 체인)를 흘려보낸다 */
async function flushMicrotasks(times = 5): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

beforeEach(async () => {
  await liveLecture.leave();
  mockRooms.length = 0;
  jest.clearAllMocks();
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
    mockedClass.issueMediaToken.mockImplementationOnce(() => tokenGate.promise);

    const h = handlers();
    const joinPromise = liveLecture.join("42", h);

    await liveLecture.leave();

    tokenGate.resolve({ serverUrl: "wss://s", token: "t", expiresAt: "x" });
    await joinPromise;

    expect(mockRooms).toHaveLength(0);
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(h.onExit).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBeNull();
  });
});
