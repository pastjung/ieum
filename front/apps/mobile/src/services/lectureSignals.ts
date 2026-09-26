/**
 * OpenVidu 방 신호 해석 (API 명세 8.5절). 네이티브 없이 테스트하려고 LiveKit 타입에 의존하지 않는다.
 *
 * 시작: 강사가 수업을 시작하면 서버가 방 metadata를 {"status":"OPEN"}으로 바꾼다.
 * 종료: 강사가 수업을 끝내면 서버가 방을 삭제하고, 참가자는 ROOM_DELETED 사유로 끊긴다.
 */

/** livekit protocol DisconnectReason.ROOM_DELETED */
export const ROOM_DELETED_REASON = 5;

export type RoomExit = "ended" | "lost" | "left";

export function isLectureOpen(metadata: string | undefined | null): boolean {
  if (!metadata) return false;
  try {
    const parsed: unknown = JSON.parse(metadata);
    return typeof parsed === "object" && parsed !== null && (parsed as { status?: unknown }).status === "OPEN";
  } catch {
    return false;
  }
}

/** 음성 장애를 수업 종료로 오인하지 않는다 — 종료는 방 삭제일 때만이다 */
export function classifyDisconnect(reason: number | undefined, leftByUser: boolean): RoomExit {
  if (reason === ROOM_DELETED_REASON) return "ended";
  if (leftByUser) return "left";
  return "lost";
}
