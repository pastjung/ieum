/**
 * ① 모델링 — 잇다 캡슐이 이해하는 액션(발화 함수)과 concept(발화 변수).
 *
 * 빅스비 캡슐의 `*.bxb`에 해당한다. 여기에는 **무엇을 이해해야 하는지**만 적고,
 * 실제 처리는 `capsule/capsule.ts`, 부수효과(이동·소리·모듈)는 앱 계층이 맡는다.
 */

/** 발화 변수. 빅스비의 input/output concept. */
export type ConceptType =
  | "ScreenName"
  | "LessonName"
  | "QuestionCount"
  | "BrailleLevel"
  | "Period"
  | "DotNumber";

export const CONCEPT_TYPES: readonly ConceptType[] = [
  "ScreenName",
  "LessonName",
  "QuestionCount",
  "BrailleLevel",
  "Period",
  "DotNumber",
];

/** 발화에서 뽑아낸 값 하나. `value`는 정규화된 값, `surface`는 실제로 말한 표현. */
export interface ConceptValue {
  readonly type: ConceptType;
  readonly value: string;
  readonly surface: string;
}

/** 발화 함수 식별자. 학습 모델의 라벨과 문자열이 같아야 한다. */
export type ActionId =
  | "OpenScreen"
  | "StartReview"
  | "ShowWrongNotes"
  | "JoinLesson"
  | "ShowProgress"
  | "ConnectDevice"
  | "RaiseDots"
  | "LowerDots"
  | "GoBack"
  | "ShowHelp"
  | "Cancel";

/** 학습 모델이 "캡슐 밖 발화"에 붙이는 라벨. 액션으로 매핑하지 않는다. */
export const OOD_LABEL = "__ood__";

export interface ActionSpec {
  readonly id: ActionId;
  readonly goal: string;
  /** 이 값이 없으면 실행할 수 없어 되묻는다(빅스비의 prompt). */
  readonly required?: readonly ConceptType[];
  readonly optional?: readonly ConceptType[];
  readonly prompts?: Readonly<Partial<Record<ConceptType, string>>>;
}

export const ACTIONS: readonly ActionSpec[] = [
  {
    id: "OpenScreen",
    goal: "말한 화면으로 이동한다",
    required: ["ScreenName"],
    prompts: { ScreenName: "어느 화면으로 갈까요? 학습, 수업, 설정처럼 말씀해 주세요." },
  },
  { id: "StartReview", goal: "학습 세션을 시작한다", optional: ["QuestionCount", "BrailleLevel"] },
  { id: "ShowWrongNotes", goal: "오답을 모아 보여준다", optional: ["Period", "BrailleLevel"] },
  // 이 앱의 수업 참여는 '참여 코드 입력' 화면에서 이뤄지므로 수업 이름을 되묻지 않는다.
  { id: "JoinLesson", goal: "수업 참여 화면으로 간다" },
  { id: "ShowProgress", goal: "학습 기록과 숙련도를 알려준다", optional: ["Period"] },
  { id: "ConnectDevice", goal: "6점 촉각 모듈(HC-06)에 연결한다" },
  {
    id: "RaiseDots",
    goal: "말한 번호의 점을 올린다",
    required: ["DotNumber"],
    prompts: { DotNumber: "몇 번 점을 올릴까요? 1번부터 6번까지 말씀해 주세요." },
  },
  { id: "LowerDots", goal: "말한 번호의 점을 내린다 (번호가 없으면 전부)", optional: ["DotNumber"] },
  { id: "GoBack", goal: "이전 화면으로 돌아간다" },
  { id: "ShowHelp", goal: "할 수 있는 말을 알려준다" },
  { id: "Cancel", goal: "요청을 취소한다" },
];

const ACTION_BY_ID = new Map(ACTIONS.map((spec) => [spec.id, spec]));

export function actionSpec(id: ActionId): ActionSpec {
  const spec = ACTION_BY_ID.get(id);
  if (!spec) throw new Error(`모르는 액션: ${id}`);
  return spec;
}

export function toActionId(label: string | null | undefined): ActionId | null {
  if (!label) return null;
  return ACTION_BY_ID.has(label as ActionId) ? (label as ActionId) : null;
}

/**
 * 이동할 수 있는 화면.
 *
 * `id`는 학습 모델의 ScreenName concept 값과 같아야 한다(모델을 다시 학습하지 않고도 쓰려면).
 * `route`는 이 앱의 expo-router 경로다 — 화면이 옮겨지면 여기만 고친다.
 */
export interface Destination {
  readonly id: string;
  readonly route: string;
  readonly title: string;
  readonly keywords: readonly string[];
}

export const DESTINATIONS: readonly Destination[] = [
  {
    id: "home",
    route: "/(tabs)",
    title: "홈",
    keywords: ["홈", "메인", "첫 화면", "처음 화면", "시작 화면"],
  },
  {
    id: "practice",
    route: "/(tabs)/learn",
    title: "학습",
    keywords: ["학습", "점자 배우기", "배우러", "배우기", "학습 탭", "테마", "주제 고르기"],
  },
  {
    id: "lesson",
    route: "/(tabs)/class",
    title: "수업",
    keywords: ["수업", "교실", "강의", "라이브", "수업 참여"],
  },
  {
    id: "settings",
    route: "/(tabs)/settings",
    title: "설정",
    keywords: ["설정", "환경설정", "세팅", "옵션"],
  },
  {
    id: "voice",
    route: "/voice-clone",
    title: "목소리 설정",
    keywords: ["목소리", "내 목소리", "목소리 복제", "음성 복제", "보이스"],
  },
  {
    id: "review",
    route: "/session/intro",
    title: "문제 풀기",
    keywords: ["문제", "문제 풀기", "문제 풀래", "연습", "복습", "오늘의 복습"],
  },
  {
    id: "wrong-note",
    route: "/class/review",
    title: "오답 복습",
    keywords: ["오답", "오답 노트", "틀린 문제", "틀린 거", "오답 복습"],
  },
  {
    id: "record",
    route: "/tools/skills",
    title: "내 실력",
    keywords: ["내 실력", "실력", "통계", "학습 기록", "기록", "진도", "성적"],
  },
  {
    id: "dictionary",
    route: "/tools/dictionary",
    title: "점자 사전",
    keywords: ["사전", "점자 사전", "낱말", "찾아보기"],
  },
  {
    id: "device",
    route: "/calibration",
    title: "손 위치 맞추기",
    keywords: ["캘리브레이션", "손 위치", "손 맞추기", "위치 맞추기"],
  },
  {
    id: "accessibility",
    route: "/onboarding/accessibility",
    title: "소리와 진동",
    keywords: ["소리와 진동", "접근성", "음성 안내", "진동 설정"],
  },
];

const DESTINATION_BY_ID = new Map(DESTINATIONS.map((d) => [d.id, d]));

export function destinationById(id: string | null | undefined): Destination | null {
  if (!id) return null;
  return DESTINATION_BY_ID.get(id) ?? null;
}

/** 액션이 끝난 뒤 데려갈 화면. 모듈만 만지는 액션은 이동하지 않는다. */
export const ACTION_DESTINATION: Readonly<Partial<Record<ActionId, string>>> = {
  StartReview: "review",
  ShowWrongNotes: "wrong-note",
  JoinLesson: "lesson",
  ShowProgress: "record",
};

// --- concept 사전 -----------------------------------------------------------

export type Gazetteer = Readonly<Record<string, readonly string[]>>;
export type Gazetteers = Readonly<Partial<Record<ConceptType, Gazetteer>>>;

export function screenGazetteer(): Gazetteer {
  return Object.fromEntries(DESTINATIONS.map((d) => [d.id, d.keywords]));
}

export const LEVEL_GAZETTEER: Gazetteer = {
  L1: ["L1", "엘원", "1단계", "일단계", "받침 없는"],
  L2: ["L2", "엘투", "2단계", "이단계", "받침"],
  L3: ["L3", "엘쓰리", "3단계", "삼단계", "약자"],
  L4: ["L4", "엘포", "4단계", "사단계", "긴 단어"],
};

export const PERIOD_GAZETTEER: Gazetteer = {
  today: ["오늘", "오늘의", "금일"],
  week: ["이번 주", "이번주", "일주일", "최근 일주일"],
  last_week: ["지난주", "지난 주", "저번 주"],
  all: ["전체", "전부", "모든", "누적"],
};

export function defaultGazetteers(): Gazetteers {
  return {
    ScreenName: screenGazetteer(),
    BrailleLevel: LEVEL_GAZETTEER,
    Period: PERIOD_GAZETTEER,
  };
}
