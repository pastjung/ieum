"""캡슐 NL 트레이닝 유스케이스 — 발화 생성 → 학습 → 평가 → 내보내기.

빅스비 개발 흐름의 "트레이닝" 단계에 해당한다.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path

from app.domain.nl.evaluator import (
    EvaluationReport,
    evaluate,
    evaluate_concepts,
    evaluate_out_of_domain,
)
from app.domain.nl.generator import GOLD_EXAMPLES, OUT_OF_DOMAIN, TrainingExample, generate
from app.domain.nl.model import NlModel
from app.domain.nl.ports import NlModelStore
from app.domain.nl.trainer import train


@dataclass(frozen=True)
class TrainingOutcome:
    model: NlModel
    train_size: int
    holdout_size: int
    reports: list[EvaluationReport]
    exported: list[Path]

    def summary(self) -> str:
        header = (
            f"학습 발화 {self.train_size}건 / 검증 {self.holdout_size}건, "
            f"라벨 {len(self.model.labels)}개, 확신도 기준 {self.model.min_confidence}"
        )
        body = "\n".join(report.summary() for report in self.reports)
        files = "\n".join(f"  → {path}" for path in self.exported)
        return "\n".join(part for part in (header, body, files) if part)


class NlTrainingService:
    """생성기·학습기·평가기를 엮는다. 저장소는 주입받아 테스트에서 갈아 끼운다."""

    def __init__(self, store: NlModelStore | None = None) -> None:
        self.store = store

    def run(
        self,
        seed: int = 20260901,
        alpha: float = 0.3,
        min_confidence: float = 0.55,
        holdout_ratio: float = 0.15,
        version: str = "1",
        export: bool = True,
    ) -> TrainingOutcome:
        examples = generate(seed=seed)
        train_set, holdout = _split(examples, holdout_ratio, seed)

        model = train(
            train_set,
            alpha=alpha,
            version=version,
            min_confidence=min_confidence,
        )

        reports = [
            evaluate(model, holdout, "holdout"),
            evaluate(model, GOLD_EXAMPLES, "gold(손으로 적은 발화)"),
            evaluate_out_of_domain(model, OUT_OF_DOMAIN),
            evaluate_concepts(holdout, model.concepts, "concept(오인식 변형 포함)"),
            evaluate_concepts(GOLD_EXAMPLES, model.concepts, "concept(gold)"),
        ]

        exported: list[Path] = []
        if export and self.store is not None:
            exported = self.store.save(model)

        return TrainingOutcome(
            model=model,
            train_size=len(train_set),
            holdout_size=len(holdout),
            reports=reports,
            exported=exported,
        )


def _split(
    examples: list[TrainingExample],
    holdout_ratio: float,
    seed: int,
) -> tuple[list[TrainingExample], list[TrainingExample]]:
    shuffled = list(examples)
    random.Random(seed).shuffle(shuffled)
    cut = int(len(shuffled) * (1.0 - holdout_ratio))
    return shuffled[:cut], shuffled[cut:]
