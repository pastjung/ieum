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

// 사용자가 뒤로 가서 화면이 빠질 때 내비게이터가 보내는 액션. 하드웨어 뒤로 가기는 GO_BACK,
// 네이티브 스택의 제스처·닫기는 POP(여러 칸이면 dismissCount)으로 온다. 앱이 router.replace로
// 화면을 바꿀 때는 REPLACE라 여기에 들지 않는다.
const USER_BACK_ACTIONS = new Set(["GO_BACK", "POP", "POP_TO_TOP"]);

/** beforeRemove의 액션이 사용자의 뒤로 가기인지 — 이때만 수업 방을 정리한다 */
export function isUserBackAction(action: { type: string }): boolean {
  return USER_BACK_ACTIONS.has(action.type);
}
