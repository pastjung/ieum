import { AudioSession } from "@livekit/react-native";
import { Room, RoomEvent } from "livekit-client";
import { classService } from "./classService";
import { classifyDisconnect, isLectureOpen, type RoomExit } from "./lectureSignals";

export interface LiveLectureHandlers {
  /** 수업이 시작됐다 (접속 1회당 최대 1번) */
  onOpen: () => void;
  /** 방에서 나왔다 — ended: 강사가 종료, lost: 음성 연결 끊김, left: 사용자가 나감 (1번) */
  onExit: (exit: RoomExit) => void;
}

interface Session {
  lectureId: string;
  room: Room;
  handlers: LiveLectureHandlers;
  opened: boolean;
  exited: boolean;
  leavingByUser: boolean;
}

/**
 * 수업 음성 방. 화면을 넘나들며 유지되므로 컴포넌트가 아니라 이 모듈이 소유한다.
 * 학습자 토큰은 수신 전용이라 마이크를 publish하지 않는다. 원격 오디오는 자동 구독·재생된다.
 */
let session: Session | null = null;

// join()이 겹치면(재시도 탭 등) 나중 호출만 유효하다. 각 join()은 시작 시 세대를 하나 찍어 가고
// 이후 await 지점마다 자신의 세대가 여전히 최신인지 확인한다 — 낡은 세대는 아무 것도 만들지 않거나,
// 이미 만든 방·오디오 세션을 핸들러 호출 없이 조용히 정리하고 물러난다.
let joinGeneration = 0;

function markOpen(current: Session): void {
  if (current.opened || current.exited || session !== current) return;
  current.opened = true;
  current.handlers.onOpen();
}

function finish(current: Session, exit: RoomExit): void {
  if (current.exited) return;
  const wasLive = session === current;
  current.exited = true;
  current.room.removeAllListeners();
  if (session === current) session = null;
  void AudioSession.stopAudioSession();
  if (wasLive) current.handlers.onExit(exit);
}

async function discardSilently(previous: Session): Promise<void> {
  // 이전 핸들러가 불리지 않게 먼저 표시하고 끊는다
  previous.exited = true;
  previous.room.removeAllListeners();
  if (session === previous) session = null;
  await previous.room.disconnect();
}

/** 낡은 세대로 판명된 join 시도가 만들어 둔 것을 핸들러 호출 없이 정리한다 */
function abandon(room: Room | null, audioStarted: boolean): void {
  if (room) {
    room.removeAllListeners();
    void room.disconnect();
  }
  if (audioStarted) void AudioSession.stopAudioSession();
}

export const liveLecture = {
  async join(lectureId: string, handlers: LiveLectureHandlers): Promise<void> {
    const generation = ++joinGeneration;
    if (session) await discardSilently(session);

    // 상태의 권위는 HTTP 스냅샷이다. 재접속이면 metadata보다 먼저 확인한다 (API 명세 8.5절)
    const lecture = await classService.getLecture(lectureId);
    if (generation !== joinGeneration) return; // 그 사이 새 입장·퇴장이 이 요청을 대체했다

    const token = await classService.issueMediaToken(lectureId);
    if (generation !== joinGeneration) return;

    await AudioSession.startAudioSession();
    if (generation !== joinGeneration) {
      abandon(null, true);
      return;
    }

    const room = new Room();
    const current: Session = { lectureId, room, handlers, opened: false, exited: false, leavingByUser: false };

    room.on(RoomEvent.RoomMetadataChanged, (metadata: string) => {
      if (isLectureOpen(metadata)) markOpen(current);
    });
    room.on(RoomEvent.Disconnected, (reason?: number) => {
      finish(current, classifyDisconnect(reason, current.leavingByUser));
    });

    if (generation !== joinGeneration) {
      current.exited = true;
      abandon(room, true);
      return;
    }

    session = current;

    try {
      await room.connect(token.serverUrl, token.token);
    } catch (error) {
      current.exited = true;
      room.removeAllListeners();
      if (session === current) session = null;
      void AudioSession.stopAudioSession();
      throw error;
    }

    if (generation !== joinGeneration) {
      current.exited = true;
      if (session === current) session = null;
      abandon(room, true);
      return;
    }

    if (lecture.status === "OPEN" || isLectureOpen(room.metadata)) markOpen(current);
  },

  async leave(): Promise<void> {
    joinGeneration++; // 대기 중이던 join()이 이후에 접속하지 못하게 한다
    const current = session;
    if (!current) return;
    current.leavingByUser = true;
    await current.room.disconnect();
    // disconnect가 Disconnected 이벤트를 내지 않는 경우에도 한 번은 알린다
    finish(current, "left");
  },

  currentLectureId(): string | null {
    return session?.lectureId ?? null;
  },
};
