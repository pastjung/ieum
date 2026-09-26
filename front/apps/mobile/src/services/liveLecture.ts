import { AndroidAudioTypePresets, AudioSession } from "@livekit/react-native";
import { ApiError } from "@ieum/api-client";
import type { MediaToken } from "@ieum/contracts";
import { Room, RoomEvent } from "livekit-client";
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";
import { classService } from "./classService";
import { pauseItdaForLecture } from "./itda";
import { canSpeak, classifyDisconnect, handFromAttributes, isLectureOpen, type RoomExit } from "./lectureSignals";

export interface LiveLectureHandlers {
  /** 수업이 시작됐다 (접속 1회당 최대 1번) */
  onOpen: () => void;
  /** 방에서 나왔다 — ended: 강사가 종료, lost: 음성 연결 끊김, left: 사용자가 나감 (1번) */
  onExit: (exit: RoomExit) => void;
  /** 선생님이 발언을 허용(true)하거나 거뒀다(false). 값이 바뀔 때만 부른다 — 화면을 옮겨도 불린다 */
  onSpeakingChanged: (allowed: boolean) => void;
}

interface Session {
  lectureId: string;
  room: Room;
  handlers: LiveLectureHandlers;
  opened: boolean;
  exited: boolean;
  leavingByUser: boolean;
  /** 이 세션을 만든 join() 호출의 세대 — 오디오 세션 소유권 확인에 쓴다 */
  generation: number;
  /** 선생님이 발언을 허용했다 — LiveKit 권한의 사본 */
  speakingAllowed: boolean;
  /** 내 마이크가 송출 중이다 */
  micOn: boolean;
  /** 손을 들었다 */
  handUp: boolean;
  /** setMicrophone이 진행 중이다 — 겹친 켜기·끄기를 막는다 */
  micBusy: boolean;
}

/**
 * 수업 음성 방. 화면을 넘나들며 유지되므로 컴포넌트가 아니라 이 모듈이 소유한다.
 * 학습자는 입장 때 말할 수 없다. 선생님이 발언을 허용하면(LiveKit 권한 변경) 스스로 마이크를 켠다.
 * 원격 오디오(선생님·다른 학생)는 자동 구독·재생된다.
 */
let session: Session | null = null;

// join()이 겹치면(재시도 탭 등) 나중 호출만 유효하다. 각 join()은 시작 시 세대를 하나 찍어 가고
// 이후 await 지점마다 자신의 세대가 여전히 최신인지 확인한다 — 낡은 세대는 아무 것도 만들지 않거나,
// 이미 만든 방·오디오 세션을 핸들러 호출 없이 조용히 정리하고 물러난다.
let joinGeneration = 0;

// AudioSession은 전역 싱글턴이다. 겹친 join()들이 각자 startAudioSession을 부를 수 있으므로,
// "지금 살아 있는 오디오는 어느 세대의 것인가"를 따로 기억해 둔다 — 낡은 join의 정리 로직이
// 이미 이긴 join의 오디오를 꺼버리지 않게 하려면 세대 비교 없이 무조건 stop()을 부르면 안 된다.
let audioOwner: number | null = null;

function markOpen(current: Session): void {
  if (current.opened || current.exited || session !== current) return;
  current.opened = true;
  current.handlers.onOpen();
}

function isLive(current: Session): boolean {
  return !current.exited && session === current;
}

/** 살아 있는 세션의 발언 상태를 화면용 스토어에 옮긴다 */
function syncVoice(current: Session): void {
  if (!isLive(current)) return;
  useLectureVoiceStore.getState().set({
    speakingAllowed: current.speakingAllowed,
    micOn: current.micOn,
    handUp: current.handUp,
  });
}

/**
 * 말할 때는 통화 프로필(에코 제거·마이크 경로), 듣기만 할 때는 미디어 프로필(스피커·미디어 음량).
 * 이 세대가 오디오를 소유할 때만 바꾼다 — 낡은 세션이 새 세션의 오디오를 건드리지 않게.
 */
function applyAudioProfile(current: Session, speaking: boolean): void {
  if (audioOwner !== current.generation) return;
  const audioTypeOptions = speaking ? AndroidAudioTypePresets.communication : AndroidAudioTypePresets.media;
  void AudioSession.configureAudio({ android: { audioTypeOptions } }).catch(() => undefined);
}

/**
 * 내 마이크 상태를 끄고 잇다에게 마이크를 돌려준다.
 * unpublish=true면 트랙도 내린다(회수 때 — LiveKit이 이미 내렸어도 무해하다).
 * 방을 떠날 때는 disconnect가 로컬 트랙을 멈추므로 false로 부른다.
 */
function releaseMic(current: Session, unpublish: boolean): void {
  const wasOn = current.micOn;
  current.micOn = false;
  if (wasOn && unpublish) void current.room.localParticipant.setMicrophoneEnabled(false).catch(() => undefined);
  pauseItdaForLecture(false); // 쉬고 있지 않았다면 아무 일도 없다
}

/** 로컬 참가자의 발언 권한이 바뀌었다 — 값이 실제로 바뀔 때만 반영하고 알린다 */
function applySpeaking(current: Session, allowed: boolean): void {
  if (!isLive(current) || current.speakingAllowed === allowed) return;
  current.speakingAllowed = allowed;
  // 허용·회수 때 백엔드가 hand를 down으로 바꾼다. 속성 이벤트가 늦게 와도 버튼이 맞게 보이게 미리 내린다
  current.handUp = false;
  if (!allowed) releaseMic(current, true);
  applyAudioProfile(current, allowed);
  syncVoice(current);
  current.handlers.onSpeakingChanged(allowed);
}

/** 이 세대가 오디오를 소유하고 있을 때만 끈다. 더 최신 세대가 이미 소유했다면 손대지 않는다 */
function releaseAudio(generation: number): void {
  if (audioOwner !== null && audioOwner !== generation) return;
  audioOwner = null;
  void AudioSession.stopAudioSession();
}

function finish(current: Session, exit: RoomExit): void {
  if (current.exited) return;
  const wasLive = session === current;
  current.exited = true;
  current.room.removeAllListeners();
  releaseMic(current, false);
  if (session === current) session = null;
  if (wasLive) useLectureVoiceStore.getState().reset();
  releaseAudio(current.generation);
  if (wasLive) current.handlers.onExit(exit);
}

async function discardSilently(previous: Session): Promise<void> {
  // 이전 핸들러가 불리지 않게 먼저 표시하고 끊는다
  previous.exited = true;
  previous.room.removeAllListeners();
  releaseMic(previous, false);
  if (session === previous) {
    session = null;
    useLectureVoiceStore.getState().reset();
  }
  await previous.room.disconnect();
  // 새 입장이 실패해도 이전 방의 오디오가 켜진 채 남지 않게 한다 (소유 확인으로 새 세대의 오디오는 건드리지 않는다)
  releaseAudio(previous.generation);
}

/** 낡은 세대로 판명된 join 시도가 만들어 둔 것을 핸들러 호출 없이 정리한다 */
function abandon(room: Room | null, generation: number): void {
  if (room) {
    room.removeAllListeners();
    void room.disconnect();
  }
  releaseAudio(generation);
}

export const liveLecture = {
  async join(lectureId: string, handlers: LiveLectureHandlers): Promise<void> {
    const generation = ++joinGeneration;
    if (session) await discardSilently(session);

    // 상태의 권위는 HTTP 스냅샷이다. 재접속이면 metadata보다 먼저 확인한다 (API 명세 8.5절)
    const lecture = await classService.getLecture(lectureId);
    if (generation !== joinGeneration) return; // 그 사이 새 입장·퇴장이 이 요청을 대체했다

    // 이미 끝난 수업이면 토큰을 받지 않고 종료로 끝낸다 (방도 오디오도 만들지 않는다)
    if (lecture.status === "CLOSED") {
      handlers.onExit("ended");
      return;
    }

    let token: MediaToken;
    try {
      token = await classService.issueMediaToken(lectureId);
    } catch (error) {
      if (generation !== joinGeneration) return;
      // 스냅샷 뒤에 수업이 닫혔다 — 재시도해도 같으니 종료로 안내한다
      if (error instanceof ApiError && error.code === "INVALID_LECTURE_STATE") {
        handlers.onExit("ended");
        return;
      }
      throw error;
    }
    if (generation !== joinGeneration) return;

    // 학습자는 듣기만 한다. 기본값(통화 모드)이면 Android가 수화기·통화 음량으로 내보내므로
    // 미디어 재생 프로필로 바꾼다. iOS는 registerGlobals의 자동 관리가 수신 전용일 때
    // playback 카테고리를 고르므로 따로 설정하지 않는다. 방 접속 전에 설정해야 적용된다
    await AudioSession.configureAudio({ android: { audioTypeOptions: AndroidAudioTypePresets.media } });
    if (generation !== joinGeneration) return;

    await AudioSession.startAudioSession();
    if (generation !== joinGeneration) {
      releaseAudio(generation);
      return;
    }
    audioOwner = generation;

    // stopMicTrackOnMute: 꺼도 mute만 되던 마이크 트랙을 실제로 멈춘다 — 아니면 캡처가 계속
    // 열려 있어 잇다가 같은 마이크로 되돌아가도 충돌·점유가 남는다 (unmute 시 재획득은 그대로 된다).
    // RoomOptions에는 없고 publishDefaults(TrackPublishDefaults)에 있다 — 리뷰가 적은 최상위
    // 자리는 설계 문서 작성 시점의 짐작이었다(설치된 livekit-client 2.22.3 타입으로 확인)
    const room = new Room({ publishDefaults: { stopMicTrackOnMute: true } });
    const current: Session = {
      lectureId,
      room,
      handlers,
      opened: false,
      exited: false,
      leavingByUser: false,
      generation,
      speakingAllowed: false,
      micOn: false,
      handUp: false,
      micBusy: false,
    };

    room.on(RoomEvent.RoomMetadataChanged, (metadata: string) => {
      if (isLectureOpen(metadata)) markOpen(current);
    });
    room.on(RoomEvent.Disconnected, (reason?: number) => {
      finish(current, classifyDisconnect(reason, current.leavingByUser));
    });
    // 선생님의 발언 허용·회수는 로컬 참가자의 권한 변경으로 온다 (speaking 속성은 표시용이라 보지 않는다)
    room.on(RoomEvent.ParticipantPermissionsChanged, (_prev, participant) => {
      if (participant === room.localParticipant) applySpeaking(current, canSpeak(participant.permissions));
    });
    room.on(RoomEvent.ParticipantAttributesChanged, (_changed, participant) => {
      if (participant !== room.localParticipant || !isLive(current)) return;
      const handUp = handFromAttributes(participant.attributes) === "up";
      if (current.handUp === handUp) return;
      current.handUp = handUp;
      syncVoice(current);
    });

    if (generation !== joinGeneration) {
      current.exited = true;
      abandon(room, generation);
      return;
    }

    session = current;
    syncVoice(current); // 새 세션은 말할 수 없는 상태로 시작한다

    try {
      await room.connect(token.serverUrl, token.token);
    } catch (error) {
      current.exited = true;
      room.removeAllListeners();
      if (session === current) session = null;
      releaseAudio(generation);
      // 접속 중에 나가거나 새 입장이 이 요청을 대체했다 — 실패가 아니라 취소이므로 조용히 끝낸다
      if (generation !== joinGeneration) return;
      throw error;
    }

    if (generation !== joinGeneration) {
      current.exited = true;
      if (session === current) session = null;
      abandon(room, generation);
      return;
    }

    // 토큰 권한의 스냅샷 — 학습자 토큰은 canPublish=false라 보통 아무 일도 없다
    applySpeaking(current, canSpeak(room.localParticipant.permissions));

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

  /** 손들기(true)·내리기(false). 발언이 허용된 동안에는 들지 않는다. 선생님께 전해지면 true */
  async raiseHand(up: boolean): Promise<boolean> {
    const current = session;
    if (!current || !isLive(current)) return false;
    if (up && current.speakingAllowed) return false;
    try {
      await current.room.localParticipant.setAttributes({ hand: up ? "up" : "down" });
    } catch {
      return false;
    }
    if (!isLive(current)) return false;
    // 대기하는 사이 선생님이 허용해 버렸다면 이 "손 듦"은 이미 낡았다 — 내려서 되돌린다
    if (up && current.speakingAllowed) {
      void current.room.localParticipant.setAttributes({ hand: "down" }).catch(() => undefined);
      return false;
    }
    current.handUp = up;
    syncVoice(current);
    return true;
  },

  /**
   * 내 마이크를 켜고(true) 끈다(false). 켜기는 선생님이 허용했을 때만 된다.
   * 원하는 상태가 되면 true, 허용 안 됨·겹친 호출·실패·그 사이 회수나 퇴장이면 false.
   * 마이크 런타임 권한(RECORD_AUDIO)은 부르는 쪽이 먼저 확인한다(micPermission).
   */
  async setMicrophone(on: boolean): Promise<boolean> {
    const current = session;
    if (!current || !isLive(current) || current.micBusy) return false;
    if (on && !current.speakingAllowed) return false;
    if (current.micOn === on) return true;

    current.micBusy = true;
    // 잇다(음성 인식)가 마이크를 먼저 놓아야 수업 마이크가 소리를 얻는다
    if (on) pauseItdaForLecture(true);
    try {
      await current.room.localParticipant.setMicrophoneEnabled(on);
    } catch {
      current.micBusy = false;
      if (on) pauseItdaForLecture(false);
      return false;
    }
    current.micBusy = false;

    // 켜는 사이 방을 떠났거나 선생님이 거뒀다 — 켜진 채 남기지 않는다
    if (on && (!isLive(current) || !current.speakingAllowed)) {
      void current.room.localParticipant.setMicrophoneEnabled(false).catch(() => undefined);
      pauseItdaForLecture(false);
      return false;
    }
    if (!isLive(current)) return false;

    // 켜지는 사이 회수→재허용이 겹치면 pause(false)가 잇다를 다시 깨웠을 수 있다 —
    // 마이크가 켜진 채로 확정되기 직전에 한 번 더 재워 둔다 (멱등)
    if (on) pauseItdaForLecture(true);
    current.micOn = on;
    if (!on) pauseItdaForLecture(false);
    syncVoice(current);
    return true;
  },

  currentLectureId(): string | null {
    return session?.lectureId ?? null;
  },
};
