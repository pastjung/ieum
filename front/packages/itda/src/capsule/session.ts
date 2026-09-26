import type { NluEngine, NluResult } from "../nlu/types";
import { isUnderstood, withConcept } from "../nlu/types";
import type { CapsuleResult, ItdaCapsule } from "./capsule";

export interface CapsuleOutcome {
  readonly nlu: NluResult;
  readonly result: CapsuleResult;
}

/**
 * 발화 한 마디를 처리하는 한 사이클 — NLU → 캡슐 실행 → (필요하면) 되묻기 상태 보관.
 *
 * 음성 입력과 텍스트 입력이 같은 경로를 타도록 여기에 모아 둔다.
 */
export class CapsuleSession {
  private prompt: Extract<CapsuleResult, { kind: "needsConcept" }> | null = null;

  constructor(
    private readonly nlu: () => NluEngine,
    private readonly capsule: ItdaCapsule,
  ) {}

  /** 되묻는 중인 질문. 비어 있으면 새 요청을 기다리는 상태. */
  get pendingQuestion(): string | null {
    return this.prompt?.speech ?? null;
  }

  async handle(utterance: string): Promise<CapsuleOutcome> {
    const parsed = this.resolve(utterance);
    const result = await this.capsule.execute(parsed);
    this.prompt = result.kind === "needsConcept" ? result : null;
    return { nlu: parsed, result };
  }

  reset(): void {
    this.prompt = null;
  }

  /**
   * 되묻는 중이면 빠진 concept만 채워 원래 액션을 이어서 실행한다.
   * 사용자가 아예 다른 요청을 하면 그쪽을 따른다.
   */
  private resolve(utterance: string): NluResult {
    const fresh = this.nlu().parse(utterance);
    const prompt = this.prompt;
    if (!prompt) return fresh;

    const filled = fresh.concepts.find((concept) => concept.type === prompt.missing);
    if (filled) return withConcept(prompt.pending, filled);
    if (isUnderstood(fresh)) return fresh;
    return prompt.pending;
  }
}
