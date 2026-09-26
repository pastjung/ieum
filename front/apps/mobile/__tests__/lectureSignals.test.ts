import { classifyDisconnect, isLectureOpen, isUserBackAction, ROOM_DELETED_REASON } from "@/services/lectureSignals";

describe("isLectureOpen", () => {
  it("OPEN 메타데이터를 인식한다", () => {
    expect(isLectureOpen('{"status":"OPEN"}')).toBe(true);
  });

  it("비었거나 다른 상태면 시작 전이다", () => {
    expect(isLectureOpen(undefined)).toBe(false);
    expect(isLectureOpen("")).toBe(false);
    expect(isLectureOpen('{"status":"SCHEDULED"}')).toBe(false);
  });

  it("JSON이 아니면 시작 전으로 본다", () => {
    expect(isLectureOpen("OPEN")).toBe(false);
  });
});

describe("classifyDisconnect", () => {
  it("방 삭제는 수업 종료다", () => {
    expect(classifyDisconnect(ROOM_DELETED_REASON, false)).toBe("ended");
  });

  it("사용자가 나간 것은 퇴장이다", () => {
    expect(classifyDisconnect(1, true)).toBe("left");
  });

  it("그 밖의 해제는 연결 끊김이다", () => {
    expect(classifyDisconnect(undefined, false)).toBe("lost");
    expect(classifyDisconnect(2, false)).toBe("lost");
  });

  it("방 삭제가 사용자 퇴장보다 우선한다", () => {
    expect(classifyDisconnect(ROOM_DELETED_REASON, true)).toBe("ended");
  });
});

describe("isUserBackAction", () => {
  it("하드웨어 뒤로 가기와 제스처 닫기는 사용자의 뒤로 가기다", () => {
    expect(isUserBackAction({ type: "GO_BACK" })).toBe(true);
    expect(isUserBackAction({ type: "POP" })).toBe(true);
    expect(isUserBackAction({ type: "POP_TO_TOP" })).toBe(true);
  });

  it("앱이 하는 화면 교체·이동은 뒤로 가기가 아니다", () => {
    expect(isUserBackAction({ type: "REPLACE" })).toBe(false);
    expect(isUserBackAction({ type: "NAVIGATE" })).toBe(false);
    expect(isUserBackAction({ type: "RESET" })).toBe(false);
  });
});
