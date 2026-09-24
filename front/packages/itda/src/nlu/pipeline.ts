import { defaultGazetteers, type ConceptType, type Gazetteer } from "../capsule/model";
import { ConceptExtractor } from "./concepts";
import { mergeGazetteers, NlModel, StatisticalNlu } from "./model";
import { RuleNlu } from "./ruleNlu";
import type { NluEngine, NluResult } from "./types";

/**
 * ASR 다음 단계.
 *
 * 학습 모델이 있으면 그쪽을 먼저 쓰고, 확신이 낮거나 모델이 없으면 규칙 NLU로 떨어진다.
 * 모델이 없어도 앱은 그대로 동작한다 — 학습은 정확도를 올리는 단계이지 전제가 아니다.
 */
export class NluPipeline implements NluEngine {
  private readonly statistical: StatisticalNlu | null;
  private readonly rule: RuleNlu;
  private readonly minConfidence: number;

  private constructor(
    private readonly model: NlModel | null,
    private readonly extractor: ConceptExtractor,
  ) {
    this.statistical = model ? new StatisticalNlu(model, extractor) : null;
    this.rule = new RuleNlu(extractor);
    this.minConfidence = model?.minConfidence ?? 1;
  }

  /** 학습 모델 JSON(없으면 null)으로 파이프라인을 만든다. */
  static create(modelJson?: string | unknown | null): NluPipeline {
    const model = modelJson == null ? null : NlModel.parse(modelJson);
    const gazetteers = model
      ? mergeGazetteers(defaultGazetteers(), model.gazetteers())
      : defaultGazetteers();
    return new NluPipeline(model, new ConceptExtractor(gazetteers));
  }

  /** 화면에 표시할 현재 NLU 구성. */
  get description(): string {
    if (!this.model) return "규칙 기반 NLU (학습 모델 없음)";
    return `학습 모델 v${this.model.version} · ${this.model.trainedAt} (+ 규칙 폴백)`;
  }

  get hasModel(): boolean {
    return this.model !== null;
  }

  parse(utterance: string): NluResult {
    if (this.statistical) {
      const result = this.statistical.parse(utterance);
      if (result.action !== null && result.confidence >= this.minConfidence) return result;
    }
    return this.rule.parse(utterance);
  }

  /** 서버에서 받아 온 동적 어휘(수업 이름 등)를 얹은 새 파이프라인. */
  withVocabulary(type: ConceptType, entries: Gazetteer): NluPipeline {
    return new NluPipeline(this.model, this.extractor.with(type, entries));
  }
}
