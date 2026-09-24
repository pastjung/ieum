import { fuzzyContains, normalize } from "../hangul";
import { DOT_ALL } from "../capsule/tactile";
import type { ActionId } from "../capsule/model";
import { ConceptExtractor } from "./concepts";
import type { NluEngine, NluResult } from "./types";

/**
 * 규칙 기반 NLU. 학습 모델이 없거나 확신이 낮을 때 쓰는 기준선(baseline)이다.
 *
 * "대상(무엇을)"은 `ConceptExtractor`가 뽑고, 여기서는 "행동(어떻게 하려는지)"만 본다.
 * 예: 복습 + 시작/하자 -> StartReview, 복습 + 열어/보여 -> OpenScreen
 */
export class RuleNlu implements NluEngine {
  constructor(private readonly extractor: ConceptExtractor = new ConceptExtractor()) {}

  parse(utterance: string): NluResult {
    const text = normalize(utterance);
    if (!text) return { utterance, action: null, concepts: [], confidence: 0, source: "none" };

    const concepts = this.extractor.extract(utterance);
    const base: NluResult = { utterance, action: null, concepts, confidence: CONFIDENCE, source: "rule" };

    const system = systemAction(text);
    if (system) return { ...base, action: system };

    const screen = concepts.find((c) => c.type === "ScreenName")?.value;
    const hasCount = concepts.some((c) => c.type === "QuestionCount");
    const dots = concepts.find((c) => c.type === "DotNumber");
    // "3번 점", "솔레노이드"처럼 촉각 모듈의 점을 가리키는 말이 있는지 ("점자"의 점은 제외).
    const mentionsDots = dots !== undefined || DOT_WORD.test(text);

    let action: ActionId | null = null;
    // 점 올리기/내리기를 화면 이동보다 먼저 본다 — "6점 켜줘"의 켜다는 화면이 아니라 점이다.
    if (mentionsDots && match(text, DOT_LOWER_VERBS)) action = "LowerDots";
    else if (mentionsDots && match(text, DOT_RAISE_VERBS)) action = "RaiseDots";
    else if (match(text, REVIEW_WORDS) && (match(text, START_VERBS) || hasCount)) action = "StartReview";
    else if (match(text, WRONG_WORDS) && match(text, SHOW_VERBS)) action = "ShowWrongNotes";
    else if (match(text, LESSON_WORDS) && match(text, JOIN_VERBS)) action = "JoinLesson";
    else if (match(text, PROGRESS_WORDS) && match(text, SHOW_VERBS)) action = "ShowProgress";
    else if (match(text, DEVICE_WORDS) && match(text, CONNECT_VERBS)) action = "ConnectDevice";
    else if (screen) action = "OpenScreen";
    else if (match(text, REVIEW_WORDS)) action = "StartReview";
    else if (match(text, WRONG_WORDS)) action = "ShowWrongNotes";
    // "3번"처럼 번호만 말해도 올리기로 본다.
    else if (dots && dots.value !== DOT_ALL) action = "RaiseDots";

    // 액션을 못 정해도 뽑아 둔 concept은 살려 둔다(되묻는 중일 때 값만 채워 넣을 수 있게).
    return { ...base, action };
  }
}

function systemAction(text: string): ActionId | null {
  if (match(text, HELP_WORDS)) return "ShowHelp";
  if (match(text, CANCEL_WORDS)) return "Cancel";
  if (match(text, BACK_WORDS)) return "GoBack";
  return null;
}

/** 인식 결과가 한 글자쯤 흔들려도("복습" -> "복십") 걸리도록 자모 거리 1까지 본다. */
function match(text: string, words: readonly string[]): boolean {
  return words.some((word) => {
    const normalized = normalize(word);
    return (
      text.includes(normalized) || (normalized.length >= 2 && fuzzyContains(text, normalized, 1))
    );
  });
}

const CONFIDENCE = 0.5;

const HELP_WORDS = ["도움말", "뭐할수있어", "뭘할수있어", "명령어", "사용법", "어떻게써", "기능알려줘"];
const CANCEL_WORDS = ["취소", "그만", "됐어", "아니야", "아니요", "종료"];
const BACK_WORDS = ["뒤로", "이전화면", "이전으로", "돌아가", "back"];

const REVIEW_WORDS = ["복습", "리뷰"];
const WRONG_WORDS = ["오답", "틀린"];
const LESSON_WORDS = ["수업", "교실", "강의", "라이브"];
const PROGRESS_WORDS = ["학습기록", "기록", "통계", "리포트", "성적", "진도", "숙련도"];
const DEVICE_WORDS = ["기기", "블루투스", "모듈", "장치", "촉각"];

const START_VERBS = ["시작", "하자", "할래", "해줘", "풀래", "풀자", "골라", "만들어", "시켜"];
const SHOW_VERBS = ["보여", "알려", "확인", "볼래", "정리", "모아", "어때"];
const JOIN_VERBS = ["들어가", "참여", "입장", "접속", "참가"];
const CONNECT_VERBS = ["연결", "페어링", "붙여", "이어", "찾아"];

const DOT_WORD = /점(?!자|수|입력)|솔레노이드/;
const DOT_RAISE_VERBS = ["올려", "올리", "켜", "세워", "띄워", "돌출", "온"];
const DOT_LOWER_VERBS = ["내려", "내리", "꺼", "끄", "오프", "낮춰", "초기화", "리셋"];
