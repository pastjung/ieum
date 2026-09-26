import type { ActionId, ConceptType, ConceptValue } from "../capsule/model";

/** 어느 경로로 해석했는지. 화면에 그대로 보여 주면 디버깅이 쉬워진다. */
export type NluSource = "model" | "rule" | "none";

/**
 * NLU 단계의 결과 — ASR이 받아쓴 문장에서
 * "무엇을 하려는지(action)"와 "그때 필요한 값(concept)"을 뽑아낸 것.
 */
export interface NluResult {
  readonly utterance: string;
  readonly action: ActionId | null;
  readonly concepts: readonly ConceptValue[];
  readonly confidence: number;
  readonly source: NluSource;
}

export interface NluEngine {
  parse(utterance: string): NluResult;
}

export function conceptOf(result: NluResult, type: ConceptType): ConceptValue | undefined {
  return result.concepts.find((concept) => concept.type === type);
}

export function conceptValue(result: NluResult, type: ConceptType): string | undefined {
  return conceptOf(result, type)?.value;
}

/** 같은 종류가 이미 있으면 갈아 끼운다. 되묻기에서 빠진 값만 채울 때 쓴다. */
export function withConcept(result: NluResult, concept: ConceptValue): NluResult {
  return {
    ...result,
    concepts: [...result.concepts.filter((c) => c.type !== concept.type), concept],
  };
}

export function isUnderstood(result: NluResult): boolean {
  return result.action !== null;
}

export function unknownResult(utterance: string): NluResult {
  return { utterance, action: null, concepts: [], confidence: 0, source: "none" };
}
