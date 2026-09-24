"""학습 결과물(다항 나이브 베이즈 파라미터)과 점수 계산.

`to_dict()`가 내보내는 JSON을 안드로이드 앱이 그대로 읽는다
(`app/src/main/java/com/itda/iva/nlu/NlModel.kt`). 두 쪽의 계산이 같아야 하므로
[classify]는 코틀린 구현과 한 줄씩 대응한다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from app.domain.nl.features import FEATURE_SPEC, extract

# 학습 때 한 번도 못 본 피처에 주는 기본 로그 확률(코틀린 폴백 값과 맞춘다).
UNSEEN_FALLBACK = -12.0


@dataclass(frozen=True)
class Prediction:
    label: str
    confidence: float


@dataclass(frozen=True)
class NlModel:
    version: str
    trained_at: str
    labels: list[str]
    log_prior: dict[str, float]
    log_likelihood: dict[str, dict[str, float]]
    default_log_likelihood: dict[str, float]
    min_confidence: float
    concepts: dict[str, dict[str, list[str]]]

    def scores(self, text: str) -> dict[str, float]:
        features = extract(text)
        result: dict[str, float] = {}
        for label in self.labels:
            weights = self.log_likelihood.get(label, {})
            fallback = self.default_log_likelihood.get(label, UNSEEN_FALLBACK)
            total = self.log_prior.get(label, math.log(1.0 / max(1, len(self.labels))))
            for feature in features:
                total += weights.get(feature, fallback)
            result[label] = total
        return result

    def classify(self, text: str) -> Prediction | None:
        """가장 점수가 높은 라벨과 소프트맥스로 정규화한 확신도."""
        if not self.labels or not extract(text):
            return None

        scores = self.scores(text)
        best_label = max(scores, key=lambda label: scores[label])
        best_score = scores[best_label]
        total = sum(math.exp(score - best_score) for score in scores.values())
        confidence = 1.0 if total <= 0 else 1.0 / total
        return Prediction(best_label, confidence)

    def to_dict(self) -> dict:
        return {
            "version": self.version,
            "trainedAt": self.trained_at,
            "featureSpec": FEATURE_SPEC,
            "labels": list(self.labels),
            "logPrior": self.log_prior,
            "logLikelihood": self.log_likelihood,
            "defaultLogLikelihood": self.default_log_likelihood,
            "minConfidence": self.min_confidence,
            "concepts": self.concepts,
        }

    @staticmethod
    def from_dict(data: dict) -> "NlModel":
        return NlModel(
            version=data.get("version", "unknown"),
            trained_at=data.get("trainedAt", ""),
            labels=list(data["labels"]),
            log_prior=data["logPrior"],
            log_likelihood=data["logLikelihood"],
            default_log_likelihood=data["defaultLogLikelihood"],
            min_confidence=float(data.get("minConfidence", 0.5)),
            concepts=data.get("concepts", {}),
        )
