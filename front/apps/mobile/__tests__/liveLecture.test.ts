import { AndroidAudioTypePresets, AudioSession } from "@livekit/react-native";
import { ApiError } from "@ieum/api-client";
import { Room } from "livekit-client";
import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";
import { pauseItdaForLecture } from "@/services/itda";
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";

const mockedRoom = Room as unknown as jest.Mock;

const mockedPauseItda = pauseItdaForLecture as unknown as jest.Mock;

type Listener = (...args: unknown[]) => void;

const mockRooms: MockRoom[] = [];
// 설정되어 있으면 다음 connect()가 이 프라미스를 기다린다(거부되면 connect도 거부된다)
let mockConnectGate: Promise<undefined> | null = null;
let mockConnectReached: (() => void) | null = null;

class MockLocalParticipant {
  permissions: { canPublish: boolean; canPublishSources: number[] } | undefined = {
    canPublish: false,
    canPublishSources: [],
  };
  attributes: Record<string, string> = {};
  setAttributes = jest.fn(async (patch: Record<string, string>) => {
    this.attributes = { ...this.attributes, ...patch };
  });
  setMicrophoneEnabled = jest.fn(async (_enabled: boolean) => undefined);
}

class MockRoom {
  metadata: string | undefined = undefined;
  localParticipant = new MockLocalParticipant();
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
  RoomEvent: {
    RoomMetadataChanged: "roomMetadataChanged",
    Disconnected: "disconnected",
    ParticipantPermissionsChanged: "participantPermissionsChanged",
    ParticipantAttributesChanged: "participantAttributesChanged",
  },
}));

// 오디오 세션이 지금 켜져 있는지 — start/stop 호출 순서를 그대로 따라간다
let mockAudioRunning = false;

jest.mock("@livekit/react-native", () => ({
  AndroidAudioTypePresets: { communication: { audioMode: "inCommunication" }, media: { audioMode: "normal" } },
  AudioSession: {
    configureAudio: jest.fn(async () => undefined),
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

jest.mock("@/services/itda", () => ({ pauseItdaForLecture: jest.fn() }));

const mockedClass = classService as unknown as { getLecture: jest.Mock; issueMediaToken: jest.Mock };
const mockedAudio = AudioSession as unknown as {
  configureAudio: jest.Mock;
  startAudioSession: jest.Mock;
  stopAudioSession: jest.Mock;
};

function handlers() {
  return { onOpen: jest.fn(), onExit: jest.fn(), onSpeakingChanged: jest.fn() };
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

const MIC = 2;

/** 백엔드가 발언을 허용했을 때 LiveKit이 로컬 참가자에게 내는 이벤트를 흉내 낸다 */
function allow(room: MockRoom): void {
  const prev = room.localParticipant.permissions;
  room.localParticipant.permissions = { canPublish: true, canPublishSources: [MIC] };
  room.localParticipant.attributes = { ...room.localParticipant.attributes, hand: "down", speaking: "allowed" };
  room.emit("participantPermissionsChanged", prev, room.localParticipant);
}

function revoke(room: MockRoom): void {
  const prev = room.localParticipant.permissions;
  room.localParticipant.permissions = { canPublish: false, canPublishSources: [] };
  room.localParticipant.attributes = { ...room.localParticipant.attributes, hand: "down", speaking: "none" };
  room.emit("participantPermissionsChanged", prev, room.localParticipant);
}

async function joinOpen(h = handlers()) {
  mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
  await liveLecture.join("42", h);
  return { h, room: mockRooms[0]! };
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

  it("stopMicTrackOnMute를 켜서 꺼진 마이크 트랙이 실제로 멈추게 한다 (I1)", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });

    await liveLecture.join("42", handlers());

    expect(mockedRoom).toHaveBeenCalledWith({ publishDefaults: { stopMicTrackOnMute: true } });
  });

  it("오디오 세션을 열기 전에 듣기 전용 미디어 오디오로 설정한다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });

    await liveLecture.join("42", handlers());

    expect(mockedAudio.configureAudio).toHaveBeenCalledWith({
      android: { audioTypeOptions: AndroidAudioTypePresets.media },
    });
    const configuredAt = mockedAudio.configureAudio.mock.invocationCallOrder[0]!;
    const startedAt = mockedAudio.startAudioSession.mock.invocationCallOrder[0]!;
    expect(configuredAt).toBeLessThan(startedAt);
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

describe("liveLecture 발언 권한", () => {
  it("입장 직후에는 말할 수 없고 알리지 않는다", async () => {
    const { h } = await joinOpen();

    expect(h.onSpeakingChanged).not.toHaveBeenCalled();
    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false, handUp: false });
  });

  it("선생님이 허용하면 한 번 알리고 상태를 바꾼다", async () => {
    const { h, room } = await joinOpen();

    allow(room);
    allow(room); // 같은 권한이 다시 와도 되풀이하지 않는다

    expect(h.onSpeakingChanged).toHaveBeenCalledTimes(1);
    expect(h.onSpeakingChanged).toHaveBeenCalledWith(true);
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(true);
  });

  it("선생님이 거두면 알리고 상태를 되돌린다", async () => {
    const { h, room } = await joinOpen();
    allow(room);

    revoke(room);

    expect(h.onSpeakingChanged).toHaveBeenLastCalledWith(false);
    expect(h.onSpeakingChanged).toHaveBeenCalledTimes(2);
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });

  it("다른 참가자의 권한 변화는 무시한다", async () => {
    const { h, room } = await joinOpen();
    const other = { permissions: { canPublish: true, canPublishSources: [MIC] }, attributes: {} };

    room.emit("participantPermissionsChanged", undefined, other);

    expect(h.onSpeakingChanged).not.toHaveBeenCalled();
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });

  it("허용되면 통화 프로필, 거두면 미디어 프로필로 오디오를 바꾼다", async () => {
    const { room } = await joinOpen();
    mockedAudio.configureAudio.mockClear();

    allow(room);
    expect(mockedAudio.configureAudio).toHaveBeenLastCalledWith({
      android: { audioTypeOptions: AndroidAudioTypePresets.communication },
    });

    revoke(room);
    expect(mockedAudio.configureAudio).toHaveBeenLastCalledWith({
      android: { audioTypeOptions: AndroidAudioTypePresets.media },
    });
  });

  it("내 hand 속성이 바뀌면 손 상태를 따라간다", async () => {
    const { room } = await joinOpen();

    room.localParticipant.attributes = { hand: "up" };
    room.emit("participantAttributesChanged", { hand: "up" }, room.localParticipant);
    expect(useLectureVoiceStore.getState().handUp).toBe(true);

    room.localParticipant.attributes = { hand: "down" };
    room.emit("participantAttributesChanged", { hand: "down" }, room.localParticipant);
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("허용되면 속성 이벤트보다 먼저 와도 손이 내려간 것으로 보인다", async () => {
    const { room } = await joinOpen();
    useLectureVoiceStore.getState().set({ handUp: true });
    room.localParticipant.attributes = { hand: "up" };
    room.emit("participantAttributesChanged", { hand: "up" }, room.localParticipant);

    allow(room);

    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("다시 입장하면 이전 방의 권한 이벤트는 누구에게도 알리지 않는다", async () => {
    const { h: first, room: oldRoom } = await joinOpen();
    // discardSilently가 removeAllListeners()를 부르면 목 방에서도 리스너가 사라져 emit이 아무 데도
    // 닿지 않는다 — 그 부수효과가 아니라 코드 안의 isLive 가드가 실제로 막는지 보려고, 해제되기
    // 전에 리스너 자체를 붙잡아 뒀다가 낡은 세션이 된 뒤에 직접 호출한다
    const staleListener = oldRoom.listeners.get("participantPermissionsChanged")![0]!;
    const second = handlers();
    await liveLecture.join("42", second);

    const prevPermissions = oldRoom.localParticipant.permissions;
    oldRoom.localParticipant.permissions = { canPublish: true, canPublishSources: [MIC] };
    staleListener(prevPermissions, oldRoom.localParticipant);

    expect(first.onSpeakingChanged).not.toHaveBeenCalled();
    expect(second.onSpeakingChanged).not.toHaveBeenCalled();
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });

  it("나가는 도중 낡은 세대에 늦게 닿은 권한 변경은 오디오 프로필을 바꾸지 않는다", async () => {
    const { room } = await joinOpen();
    allow(room); // 통화 프로필로 이미 전환돼 있다
    // finish()가 removeAllListeners()를 부르면 목 방에서도 리스너가 사라진다 — 그 부수효과가
    // 아니라 applySpeaking/applyAudioProfile 안의 가드가 실제로 막는지 보려고 해제되기 전에
    // 리스너를 붙잡아 뒀다가 방을 나간 뒤에 직접 호출한다(늦게 도착한 이벤트를 흉내낸다)
    const staleListener = room.listeners.get("participantPermissionsChanged")![0]!;

    await liveLecture.leave();
    mockedAudio.configureAudio.mockClear();

    const prevPermissions = room.localParticipant.permissions;
    room.localParticipant.permissions = { canPublish: false, canPublishSources: [] };
    staleListener(prevPermissions, room.localParticipant);

    expect(mockedAudio.configureAudio).not.toHaveBeenCalled();
  });

  it("방을 떠나면 발언 상태를 비운다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await liveLecture.leave();

    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false, handUp: false });
  });

  it("방이 삭제돼도 발언 상태를 비운다", async () => {
    const { room } = await joinOpen();
    allow(room);

    room.emit("disconnected", 5);

    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });
});

describe("liveLecture 손들기·마이크", () => {
  it("세션이 없으면 손들기·마이크 모두 거절한다", async () => {
    await expect(liveLecture.raiseHand(true)).resolves.toBe(false);
    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);
  });

  it("손을 들면 hand 속성을 up으로 보내고 상태를 바꾼다", async () => {
    const { room } = await joinOpen();

    await expect(liveLecture.raiseHand(true)).resolves.toBe(true);

    expect(room.localParticipant.setAttributes).toHaveBeenCalledWith({ hand: "up" });
    expect(useLectureVoiceStore.getState().handUp).toBe(true);
  });

  it("손을 내리면 hand 속성을 down으로 보낸다", async () => {
    const { room } = await joinOpen();
    await liveLecture.raiseHand(true);

    await expect(liveLecture.raiseHand(false)).resolves.toBe(true);

    expect(room.localParticipant.setAttributes).toHaveBeenLastCalledWith({ hand: "down" });
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("발언이 허용된 동안에는 손을 들지 않는다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await expect(liveLecture.raiseHand(true)).resolves.toBe(false);
    expect(room.localParticipant.setAttributes).not.toHaveBeenCalled();
  });

  it("속성 전송이 실패하면 손 상태를 바꾸지 않는다", async () => {
    const { room } = await joinOpen();
    room.localParticipant.setAttributes.mockRejectedValueOnce(new Error("timeout"));

    await expect(liveLecture.raiseHand(true)).resolves.toBe(false);
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("손들기 응답을 기다리는 사이 선생님이 허용하면 손을 내리고 거절한다 (M2)", async () => {
    const { room } = await joinOpen();
    const gate = deferred<void>();
    room.localParticipant.setAttributes.mockImplementationOnce(() => gate.promise);

    const raising = liveLecture.raiseHand(true);
    allow(room); // 대기 중에 발언이 허용됐다 — 이 손들기는 이미 낡았다
    gate.resolve(undefined);

    await expect(raising).resolves.toBe(false);
    expect(room.localParticipant.setAttributes).toHaveBeenLastCalledWith({ hand: "down" });
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("허용 전에는 마이크를 켜지 않는다", async () => {
    const { room } = await joinOpen();

    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);

    expect(room.localParticipant.setMicrophoneEnabled).not.toHaveBeenCalled();
    expect(mockedPauseItda).not.toHaveBeenCalledWith(true);
  });

  it("허용되면 잇다를 먼저 쉬게 한 뒤 마이크를 켠다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await expect(liveLecture.setMicrophone(true)).resolves.toBe(true);

    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenCalledWith(true);
    const pausedAt = mockedPauseItda.mock.invocationCallOrder[mockedPauseItda.mock.calls.findIndex((c) => c[0] === true)]!;
    const enabledAt = room.localParticipant.setMicrophoneEnabled.mock.invocationCallOrder[0]!;
    expect(pausedAt).toBeLessThan(enabledAt);
    expect(useLectureVoiceStore.getState().micOn).toBe(true);
  });

  it("마이크를 끄면 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    await expect(liveLecture.setMicrophone(false)).resolves.toBe(true);

    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false);
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("이미 그 상태면 다시 부르지 않고 성공으로 본다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await expect(liveLecture.setMicrophone(false)).resolves.toBe(true);
    expect(room.localParticipant.setMicrophoneEnabled).not.toHaveBeenCalled();
  });

  it("마이크 켜기가 실패하면 꺼진 상태로 두고 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    room.localParticipant.setMicrophoneEnabled.mockRejectedValueOnce(new Error("NotAllowedError"));

    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);

    expect(mockedPauseItda).toHaveBeenLastCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("켜는 중에 겹쳐 누르면 나중 요청은 거절한다", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const first = liveLecture.setMicrophone(true);
    await expect(liveLecture.setMicrophone(false)).resolves.toBe(false);
    gate.resolve(undefined);

    await expect(first).resolves.toBe(true);
    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenCalledTimes(1);
  });

  it("켜지는 사이 회수→재허용이 겹쳐도 켜진 순간 잇다를 다시 재운다 (M1)", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const turningOn = liveLecture.setMicrophone(true);
    // pause(false)가 잇다를 깨웠다가, 곧바로 재허용된다
    revoke(room);
    allow(room);
    mockedPauseItda.mockClear();
    gate.resolve(undefined);

    await expect(turningOn).resolves.toBe(true);
    expect(mockedPauseItda).toHaveBeenLastCalledWith(true);
    expect(useLectureVoiceStore.getState().micOn).toBe(true);
  });

  it("선생님이 거두면 마이크를 끄고 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    revoke(room);

    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false);
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false });
    // 거둔 뒤에는 다시 켤 수 없다
    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);
  });

  it("켜는 사이 거둬지면 켜진 채 남기지 않는다", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const turningOn = liveLecture.setMicrophone(true);
    revoke(room);
    gate.resolve(undefined);

    await expect(turningOn).resolves.toBe(false);
    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false);
    expect(mockedPauseItda).toHaveBeenLastCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("마이크를 켠 채 나가면 잇다를 돌려주고 상태를 비운다", async () => {
    const { room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    await liveLecture.leave();

    expect(room.disconnect).toHaveBeenCalled(); // disconnect가 로컬 트랙을 멈춘다
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("마이크를 켠 채 수업이 끝나도 잇다를 돌려준다", async () => {
    const { h, room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    room.emit("disconnected", 5);

    expect(h.onExit).toHaveBeenCalledWith("ended");
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
  });

  it("마이크를 켠 채 다시 입장하면 이전 방의 마이크를 정리하고 새 방은 꺼진 채 시작한다", async () => {
    const { room: oldRoom } = await joinOpen();
    allow(oldRoom);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    await liveLecture.join("42", handlers());

    expect(oldRoom.disconnect).toHaveBeenCalled();
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false });
    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);
  });

  it("켜는 사이 방을 떠나면 true를 돌려주지 않고 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const turningOn = liveLecture.setMicrophone(true);
    await liveLecture.leave();
    gate.resolve(undefined);

    await expect(turningOn).resolves.toBe(false);
    expect(mockedPauseItda).toHaveBeenLastCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });
});
