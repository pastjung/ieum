import { toActionId, type ConceptType, type Gazetteer, type Gazetteers } from "../capsule/model";
import { ConceptExtractor } from "./concepts";
import { extractFeatures } from "./features";
import { unknownResult, type NluEngine, type NluResult } from "./types";

/**
 * 학습된 의도 분류 모델(다항 나이브 베이즈).
 *
 * 학습(발화 생성 + 파라미터 추정)은 `apps/ai` 파이썬 파이프라인이 하고,
 * 앱은 그 결과 JSON을 읽어 **점수만** 계산한다.
 */
export interface NlModelData {
  readonly version: string;
  readonly trainedAt: string;
  readonly labels: readonly string[];
  readonly logPrior: Readonly<Record<string, number>>;
  readonly logLikelihood: Readonly<Record<string, Readonly<Record<string, number>>>>;
  readonly defaultLogLikelihood: Readonly<Record<string, number>>;
  readonly minConfidence: number;
  readonly concepts?: Readonly<Record<string, Gazetteer>>;
}

export interface Prediction {
  readonly label: string;
  readonly confidence: number;
}

/** 학습에서 한 번도 못 본 피처에 주는 기본 로그 확률. */
const UNSEEN_FALLBACK = -12;

export class NlModel {
  private constructor(private readonly data: NlModelData) {}

  /** 학습 파이프라인이 내보낸 JSON. 형식이 어긋나면 null. */
  static parse(json: string | unknown): NlModel | null {
    try {
      const raw = (typeof json === "string" ? JSON.parse(json) : json) as Partial<NlModelData>;
      if (!Array.isArray(raw?.labels) || raw.labels.length === 0) return null;
      if (!raw.logPrior || !raw.logLikelihood || !raw.defaultLogLikelihood) return null;

      return new NlModel({
        version: raw.version ?? "unknown",
        trainedAt: raw.trainedAt ?? "",
        labels: raw.labels,
        logPrior: raw.logPrior,
        logLikelihood: raw.logLikelihood,
        defaultLogLikelihood: raw.defaultLogLikelihood,
        minConfidence: raw.minConfidence ?? 0.5,
        concepts: raw.concepts,
      });
    } catch {
      return null;
    }
  }

  get version(): string {
    return this.data.version;
  }

  get trainedAt(): string {
    return this.data.trainedAt;
  }

  get labels(): readonly string[] {
    return this.data.labels;
  }

  get minConfidence(): number {
    return this.data.minConfidence;
  }

  /** 모델이 실어 보낸 concept 사전. 앱 기본 사전 위에 얹어 쓴다. */
  gazetteers(): Gazetteers {
    const source = this.data.concepts;
    if (!source) return {};
    const out: Record<string, Gazetteer> = {};
    for (const [conceptId, values] of Object.entries(source)) out[conceptId] = values;
    return out as Gazetteers;
  }

  /** 가장 점수가 높은 라벨과, 소프트맥스로 정규화한 확신도. */
  classify(utterance: string): Prediction | null {
    const features = extractFeatures(utterance);
    if (features.length === 0) return null;

    const uniformPrior = Math.log(1 / this.data.labels.length);
    const scores = new Map<string, number>();

    for (const label of this.data.labels) {
      const weights = this.data.logLikelihood[label] ?? {};
      const fallback = this.data.defaultLogLikelihood[label] ?? UNSEEN_FALLBACK;
      let total = this.data.logPrior[label] ?? uniformPrior;
      for (const feature of features) {
        total += weights[feature] ?? fallback;
      }
      scores.set(label, total);
    }

    let bestLabel = this.data.labels[0];
    let bestScore = Number.NEGATIVE_INFINITY;
    for (const [label, score] of scores) {
      if (score > bestScore) {
        bestScore = score;
        bestLabel = label;
      }
    }

    let sum = 0;
    for (const score of scores.values()) sum += Math.exp(score - bestScore);
    return { label: bestLabel, confidence: sum <= 0 ? 1 : 1 / sum };
  }
}

/** 학습 모델로 의도를 정하고, concept은 사전 추출기로 뽑는다. */
export class StatisticalNlu implements NluEngine {
  constructor(
    private readonly model: NlModel,
    private readonly extractor: ConceptExtractor,
  ) {}

  parse(utterance: string): NluResult {
    const prediction = this.model.classify(utterance);
    if (!prediction) return unknownResult(utterance);

    // 캡슐 밖 발화(__ood__)는 액션으로 매핑되지 않아 자연스럽게 걸러진다.
    const action = toActionId(prediction.label);
    if (!action) return unknownResult(utterance);

    return {
      utterance,
      action,
      concepts: this.extractor.extract(utterance),
      confidence: prediction.confidence,
      source: "model",
    };
  }
}

/** 모델 사전을 앱 기본 사전 **위에** 얹는다(값 단위 병합 — 모델이 모르는 화면도 살아남는다). */
export function mergeGazetteers(base: Gazetteers, extra: Gazetteers): Gazetteers {
  const merged: Record<string, Gazetteer> = { ...(base as Record<string, Gazetteer>) };
  for (const [type, entries] of Object.entries(extra)) {
    if (!entries) continue;
    merged[type] = { ...(merged[type] ?? {}), ...entries };
  }
  return merged as Gazetteers;
}

export type { ConceptType };
