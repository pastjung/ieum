import {
  canSpeak,
  classifyDisconnect,
  currentProblemIdFrom,
  handFromAttributes,
  isLectureOpen,
  isUserBackAction,
  MICROPHONE_SOURCE,
  ROOM_DELETED_REASON,
} from "@/services/lectureSignals";

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

describe("canSpeak", () => {
  it("게시 권한과 마이크 소스가 있으면 말할 수 있다", () => {
    expect(canSpeak({ canPublish: true, canPublishSources: [MICROPHONE_SOURCE] })).toBe(true);
  });

  it("소스 목록이 비어 있으면 모든 소스가 허용된 것이다 (LiveKit 규칙)", () => {
    expect(canSpeak({ canPublish: true, canPublishSources: [] })).toBe(true);
  });

  it("게시 권한이 없으면 말할 수 없다", () => {
    expect(canSpeak({ canPublish: false, canPublishSources: [MICROPHONE_SOURCE] })).toBe(false);
    expect(canSpeak({ canPublish: false, canPublishSources: [] })).toBe(false);
  });

  it("마이크가 아닌 소스만 허용되면 말할 수 없다", () => {
    expect(canSpeak({ canPublish: true, canPublishSources: [1] })).toBe(false);
  });

  it("권한 정보가 아직 없으면 말할 수 없다", () => {
    expect(canSpeak(undefined)).toBe(false);
  });
});

describe("handFromAttributes", () => {
  it("hand가 up이면 손을 든 것이다", () => {
    expect(handFromAttributes({ hand: "up" })).toBe("up");
  });

  it("down·다른 값·없음은 모두 내린 것이다", () => {
    expect(handFromAttributes({ hand: "down" })).toBe("down");
    expect(handFromAttributes({ hand: "UP" })).toBe("down");
    expect(handFromAttributes({ speaking: "allowed" })).toBe("down");
    expect(handFromAttributes({})).toBe("down");
    expect(handFromAttributes(undefined)).toBe("down");
  });
});

describe("currentProblemIdFrom", () => {
  it("metadata의 현재 문제 ID를 읽는다", () => {
    expect(currentProblemIdFrom('{"status":"OPEN","currentProblemId":"26578"}')).toBe("26578");
  });

  it("현재 문제가 없거나 null이면 null이다", () => {
    expect(currentProblemIdFrom('{"status":"OPEN"}')).toBeNull();
    expect(currentProblemIdFrom('{"status":"OPEN","currentProblemId":null}')).toBeNull();
    expect(currentProblemIdFrom('{"status":"OPEN","currentProblemId":""}')).toBeNull();
  });

  it("문자열이 아닌 ID·JSON이 아닌 값·빈 값은 null이다", () => {
    expect(currentProblemIdFrom('{"currentProblemId":26578}')).toBeNull();
    expect(currentProblemIdFrom("not-json")).toBeNull();
    expect(currentProblemIdFrom('"26578"')).toBeNull();
    expect(currentProblemIdFrom("")).toBeNull();
    expect(currentProblemIdFrom(undefined)).toBeNull();
    expect(currentProblemIdFrom(null)).toBeNull();
  });
});
