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

function markOpen(current: Session): void {
  if (current.opened || current.exited) return;
  current.opened = true;
  current.handlers.onOpen();
}

function finish(current: Session, exit: RoomExit): void {
  if (current.exited) return;
  current.exited = true;
  current.room.removeAllListeners();
  if (session === current) session = null;
  void AudioSession.stopAudioSession();
  current.handlers.onExit(exit);
}

async function discardSilently(previous: Session): Promise<void> {
  // 이전 핸들러가 불리지 않게 먼저 표시하고 끊는다
  previous.exited = true;
  previous.room.removeAllListeners();
  if (session === previous) session = null;
  await previous.room.disconnect();
}

export const liveLecture = {
  async join(lectureId: string, handlers: LiveLectureHandlers): Promise<void> {
    if (session) await discardSilently(session);

    // 상태의 권위는 HTTP 스냅샷이다. 재접속이면 metadata보다 먼저 확인한다 (API 명세 8.5절)
    const lecture = await classService.getLecture(lectureId);
    const token = await classService.issueMediaToken(lectureId);

    await AudioSession.startAudioSession();
    const room = new Room();
    const current: Session = { lectureId, room, handlers, opened: false, exited: false, leavingByUser: false };
    session = current;

    room.on(RoomEvent.RoomMetadataChanged, (metadata: string) => {
      if (isLectureOpen(metadata)) markOpen(current);
    });
    room.on(RoomEvent.Disconnected, (reason?: number) => {
      finish(current, classifyDisconnect(reason, current.leavingByUser));
    });

    try {
      await room.connect(token.serverUrl, token.token);
    } catch (error) {
      current.exited = true;
      room.removeAllListeners();
      if (session === current) session = null;
      void AudioSession.stopAudioSession();
      throw error;
    }

    if (lecture.status === "OPEN" || isLectureOpen(room.metadata)) markOpen(current);
  },

  async leave(): Promise<void> {
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
