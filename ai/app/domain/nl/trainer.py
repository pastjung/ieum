"""다항 나이브 베이즈 학습.

의도 분류는 라벨이 아홉 개, 발화가 짧고 어휘가 좁아서
가벼운 선형 모델로 충분하다. 외부 라이브러리 없이 순수 파이썬으로 계산하므로
학습 결과를 그대로 JSON으로 내보내 앱에서 되풀이할 수 있다.

    P(label | 발화) ∝ P(label) * Π P(피처 | label)

라플라스 스무딩(alpha)으로 학습에서 못 본 피처를 처리하고,
그 기본값을 `defaultLogLikelihood`로 함께 내보낸다.
"""

from __future__ import annotations

import math
from collections import Counter, defaultdict
from datetime import datetime, timezone

from app.domain.nl.features import extract
from app.domain.nl.generator import TrainingExample
from app.domain.nl.model import NlModel
from app.domain.nl.schema import concept_gazetteers

# 라벨마다 피처 총량을 이 값으로 맞춘다(클래스 불균형 보정).
NORMALIZED_TOTAL = 10_000.0


def train(
    examples: list[TrainingExample],
    alpha: float = 0.3,
    version: str = "1",
    min_confidence: float = 0.55,
    trained_at: str | None = None,
    uniform_prior: bool = True,
) -> NlModel:
    if not examples:
        raise ValueError("학습 발화가 비어 있습니다.")

    label_counts: Counter[str] = Counter()
    feature_counts: dict[str, Counter[str]] = defaultdict(Counter)
    vocabulary: set[str] = set()

    for example in examples:
        label_counts[example.action] += 1
        features = extract(example.text)
        feature_counts[example.action].update(features)
        vocabulary.update(features)

    labels = sorted(label_counts)
    vocabulary_size = max(1, len(vocabulary))
    total_examples = sum(label_counts.values())

    log_prior: dict[str, float] = {}
    log_likelihood: dict[str, dict[str, float]] = {}
    default_log_likelihood: dict[str, float] = {}

    for label in labels:
        counts = feature_counts[label]
        total = sum(counts.values())

        # 클래스마다 학습 문장 양이 다르면(템플릿 수가 다르므로) 분모가 달라져서,
        # 학습 문장이 적은 라벨일수록 "못 본 피처"의 확률이 높아진다.
        # 그러면 처음 보는 긴 문장이 전부 작은 라벨로 끌려간다.
        # 그래서 라벨별 피처 총량을 같은 값으로 맞춘 뒤 스무딩한다.
        scale = NORMALIZED_TOTAL / total if total else 0.0
        denominator = NORMALIZED_TOTAL + alpha * vocabulary_size

        # 템플릿 개수는 실제 사용 빈도가 아니므로 기본값은 균등 사전확률이다.
        log_prior[label] = (
            math.log(1.0 / len(labels))
            if uniform_prior
            else math.log(label_counts[label] / total_examples)
        )
        log_likelihood[label] = {
            feature: round(math.log((count * scale + alpha) / denominator), 6)
            for feature, count in counts.items()
        }
        default_log_likelihood[label] = round(math.log(alpha / denominator), 6)

    return NlModel(
        version=version,
        trained_at=trained_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        labels=labels,
        log_prior={label: round(value, 6) for label, value in log_prior.items()},
        log_likelihood=log_likelihood,
        default_log_likelihood=default_log_likelihood,
        min_confidence=min_confidence,
        concepts=concept_gazetteers(),
    )
