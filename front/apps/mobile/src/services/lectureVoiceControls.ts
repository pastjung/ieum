/** 수업 발언 버튼의 라벨·힌트와 안내 문구 — 화면 밖에서 테스트하려고 순수 함수로 둔다 */

export const SPEAKING_ALLOWED_MESSAGE = "선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요.";
export const SPEAKING_REVOKED_MESSAGE = "선생님이 발언을 마쳤어요.";
export const MIC_LOCKED_HINT = "선생님이 허용하면 말할 수 있어요";
export const MIC_PERMISSION_DENIED_MESSAGE = "설정에서 마이크 권한을 허용해 주세요";
/** 켜기 **전에** 읽는다 — 켠 뒤에 읽으면 안내 음성이 수업에 그대로 전해진다 */
export const MIC_ON_MESSAGE = "마이크를 켤게요. 말씀하세요.";
export const MIC_OFF_MESSAGE = "마이크를 껐어요.";
export const MIC_FAILED_MESSAGE = "마이크를 바꾸지 못했어요. 다시 눌러 주세요.";
export const HAND_UP_MESSAGE = "손을 들었어요. 선생님이 허용하면 알려 드릴게요.";
export const HAND_DOWN_MESSAGE = "손을 내렸어요.";
export const HAND_FAILED_MESSAGE = "선생님께 전하지 못했어요. 다시 눌러 주세요.";

export interface LectureVoiceView {
  speakingAllowed: boolean;
  micOn: boolean;
  handUp: boolean;
}

export interface LectureVoiceControls {
  /** 발언이 허용된 동안에는 null(숨김) */
  hand: { label: string; hint: string } | null;
  mic: { label: string; hint: string; disabled: boolean };
  /** 상태 줄 접근성 라벨에 붙일 말. 알릴 것이 없으면 "" */
  status: string;
}

export function lectureVoiceControls(view: LectureVoiceView): LectureVoiceControls {
  const hand = view.speakingAllowed
    ? null
    : view.handUp
      ? { label: "손 내리기", hint: "선생님께 한 발언 요청을 거둬요" }
      : { label: "손들기", hint: "선생님께 말하고 싶다고 알려요" };

  const mic = !view.speakingAllowed
    ? { label: "마이크 켜기", hint: MIC_LOCKED_HINT, disabled: true }
    : view.micOn
      ? { label: "마이크 끄기", hint: "누르면 내 목소리가 더 이상 전해지지 않아요", disabled: false }
      : { label: "마이크 켜기", hint: "누르면 선생님과 친구들이 내 목소리를 들어요", disabled: false };

  const status = view.speakingAllowed
    ? view.micOn
      ? "내 마이크 켜짐"
      : "발언 허용됨, 마이크 꺼짐"
    : view.handUp
      ? "손 든 상태"
      : "";

  return { hand, mic, status };
}
