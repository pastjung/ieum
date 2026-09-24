/**
 * @ieum/itda — 음성 비서 "잇다"의 두뇌.
 *
 * 순수 TypeScript다. React/RN/브라우저 API를 쓰지 않으므로 기기 없이 테스트된다.
 * 마이크·소리·블루투스 같은 부수효과는 앱 계층이 맡고, 이 패키지는
 * "무슨 말인지"와 "무엇을 할지"만 정한다.
 *
 * ```
 * 받아쓴 문장 ─ NluPipeline ─▶ (action, concepts) ─ ItdaCapsule ─▶ 말할 것 + 갈 곳 + 모듈 명령
 * ```
 */

// 한글 유틸 — 웨이크워드·사전 매칭의 바탕
export {
  editDistance,
  fuzzyContains,
  fuzzyIndexOfEnd,
  jamoDistance,
  normalize,
  toJamo,
} from "./hangul";

// 녹음 → WAV (음성 복제 업로드용)
export { buildWav, wavHeader, WAV_HEADER_SIZE } from "./wav";

// 호출어
export { detectWakeWord, detectWakeWordIn, WAKE_PHRASES } from "./wakeWord";
export type { WakePhrase, WakeResult } from "./wakeWord";

// ① 모델링
export {
  ACTION_DESTINATION,
  ACTIONS,
  actionSpec,
  CONCEPT_TYPES,
  defaultGazetteers,
  DESTINATIONS,
  destinationById,
  LEVEL_GAZETTEER,
  OOD_LABEL,
  PERIOD_GAZETTEER,
  screenGazetteer,
  toActionId,
} from "./capsule/model";
export type {
  ActionId,
  ActionSpec,
  ConceptType,
  ConceptValue,
  Destination,
  Gazetteer,
  Gazetteers,
} from "./capsule/model";

// ② NLU
export { ConceptExtractor } from "./nlu/concepts";
export { extractFeatures, FEATURE_SPEC } from "./nlu/features";
export { mergeGazetteers, NlModel, StatisticalNlu } from "./nlu/model";
export type { NlModelData, Prediction } from "./nlu/model";
export { NluPipeline } from "./nlu/pipeline";
export { RuleNlu } from "./nlu/ruleNlu";
export {
  conceptOf,
  conceptValue,
  isUnderstood,
  unknownResult,
  withConcept,
} from "./nlu/types";
export type { NluEngine, NluResult, NluSource } from "./nlu/types";

// ③ 캡슐
export { ItdaCapsule } from "./capsule/capsule";
export type { CapsuleResult, ItdaCapsuleOptions, Navigation } from "./capsule/capsule";
export { CapsuleSession } from "./capsule/session";
export type { CapsuleOutcome } from "./capsule/session";

// ④ 촉각 모듈
export {
  applyTo,
  decodeDots,
  describeCommand,
  DOT_ALL,
  encodeDots,
  InMemoryTactileDevice,
  isDot,
  labelDots,
  LINE_END,
  TactileUnavailableError,
  toSerialLine,
} from "./capsule/tactile";
export type { TactileCommand, TactileDevice } from "./capsule/tactile";
