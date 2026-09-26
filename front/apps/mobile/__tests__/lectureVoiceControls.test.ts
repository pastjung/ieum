import {
  lectureVoiceControls,
  MIC_LOCKED_HINT,
  setLecturePlayScreenActive,
  speakingAllowedAnnouncement,
  SPEAKING_ALLOWED_MESSAGE,
  SPEAKING_ALLOWED_MESSAGE_NEUTRAL,
  SPEAKING_REVOKED_MESSAGE,
  MIC_PERMISSION_DENIED_MESSAGE,
} from "@/services/lectureVoiceControls";

describe("lectureVoiceControls", () => {
  it("허용 전: 손들기 버튼이 있고 마이크는 잠겨 있다", () => {
    const c = lectureVoiceControls({ speakingAllowed: false, micOn: false, handUp: false });
    expect(c.hand?.label).toBe("손들기");
    expect(c.mic).toEqual({ label: "마이크 켜기", hint: MIC_LOCKED_HINT, disabled: true });
    expect(c.status).toBe("");
  });

  it("손을 든 상태: 손 내리기", () => {
    const c = lectureVoiceControls({ speakingAllowed: false, micOn: false, handUp: true });
    expect(c.hand?.label).toBe("손 내리기");
    expect(c.status).toBe("손 든 상태");
  });

  it("허용되면 손들기를 숨기고 마이크를 켤 수 있다", () => {
    const c = lectureVoiceControls({ speakingAllowed: true, micOn: false, handUp: false });
    expect(c.hand).toBeNull();
    expect(c.mic.label).toBe("마이크 켜기");
    expect(c.mic.disabled).toBe(false);
    expect(c.status).toBe("발언 허용됨, 마이크 꺼짐");
  });

  it("마이크가 켜져 있으면 끄기", () => {
    const c = lectureVoiceControls({ speakingAllowed: true, micOn: true, handUp: false });
    expect(c.mic.label).toBe("마이크 끄기");
    expect(c.mic.disabled).toBe(false);
    expect(c.status).toBe("내 마이크 켜짐");
  });

  it("설계서 문구를 그대로 쓴다", () => {
    expect(SPEAKING_ALLOWED_MESSAGE).toBe("선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요.");
    expect(SPEAKING_REVOKED_MESSAGE).toBe("선생님이 발언을 마쳤어요.");
    expect(MIC_LOCKED_HINT).toBe("선생님이 허용하면 말할 수 있어요");
    expect(MIC_PERMISSION_DENIED_MESSAGE).toBe("설정에서 마이크 권한을 허용해 주세요");
  });

  describe("speakingAllowedAnnouncement (M5)", () => {
    afterEach(() => {
      setLecturePlayScreenActive(false); // 다음 테스트에 기본값(꺼짐)으로 되돌린다
    });

    it("풀이 화면이 아니면 마이크 버튼을 누르라고 하지 않는다", () => {
      expect(speakingAllowedAnnouncement()).toBe(SPEAKING_ALLOWED_MESSAGE_NEUTRAL);
    });

    it("풀이 화면에서는 마이크 버튼을 누르라고 안내한다", () => {
      setLecturePlayScreenActive(true);
      expect(speakingAllowedAnnouncement()).toBe(SPEAKING_ALLOWED_MESSAGE);
    });
  });
});
