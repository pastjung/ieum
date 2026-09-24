"""학습한 캡슐이 실제로 말귀를 알아듣는지 재는 단계(빅스비의 디버깅에 해당).

세 가지를 따로 본다.
1. holdout    — 생성 발화 중 학습에 쓰지 않은 몫. 과적합 여부.
2. gold       — 손으로 적은 발화. 템플릿 밖 일반화 성능.
3. OOD        — 캡슐이 처리할 수 없는 발화. 확신도로 걸러 내는지.

concept 추출은 앱과 같은 사전(gazetteer)을 쓰는 단순 매칭이라
여기서도 같은 방식으로 재서 사전 자체가 맞는지 확인한다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from app.domain.nl.features import fuzzy_contains, normalize
from app.domain.nl.generator import TrainingExample
from app.domain.nl.model import NlModel
from app.domain.nl.schema import OOD_LABEL

_DIGIT_COUNT = re.compile(r"(\d{1,3})(?:문제|문항|개)")

_KOREAN_NUMBERS: list[tuple[str, str]] = [
    ("스물다섯", "25"), ("스무", "20"), ("스물", "20"),
    ("열다섯", "15"), ("열두", "12"), ("열한", "11"), ("열", "10"),
    ("아홉", "9"), ("여덟", "8"), ("일곱", "7"), ("여섯", "6"), ("다섯", "5"),
    ("서른", "30"), ("마흔", "40"), ("쉰", "50"),
    ("네", "4"), ("넷", "4"), ("세", "3"), ("셋", "3"), ("두", "2"), ("둘", "2"),
    ("한", "1"), ("하나", "1"),
]

_COUNT_UNITS = ("문제", "문항", "개")

# 점 번호 — 앱 ConceptExtractor.extractDots 와 같은 규칙. "점자", "6점 입력"의 점은 제외.
_DOT_NUMBER = re.compile(r"(\d{1,6}|다섯|여섯|하나|한|두|세|네|첫|일|이|삼|사|오|육|륙)(?:번째|번|점(?!자|수|입력))")
_DOT_NOISE = re.compile(r"이번(?=주|달|에|엔|은|시간|기회)")
_DOT_ALL = re.compile(r"(?:전부|모두|모든|전체|다)점?(?=올려|올리|내려|내리|켜|꺼|끄|세워|해제|초기화)|(?:전부|모두|전체)$")
_KOREAN_DIGITS = {
    "일": 1, "하나": 1, "한": 1, "첫": 1,
    "이": 2, "두": 2,
    "삼": 3, "세": 3,
    "사": 4, "네": 4,
    "오": 5, "다섯": 5,
    "육": 6, "륙": 6, "여섯": 6,
}


@dataclass(frozen=True)
class LabelScore:
    label: str
    support: int
    correct: int

    @property
    def accuracy(self) -> float:
        return self.correct / self.support if self.support else 0.0


@dataclass
class EvaluationReport:
    name: str
    total: int = 0
    correct: int = 0
    rejected: int = 0
    per_label: dict[str, LabelScore] = field(default_factory=dict)
    errors: list[tuple[str, str, str, float]] = field(default_factory=list)

    @property
    def accuracy(self) -> float:
        return self.correct / self.total if self.total else 0.0

    def summary(self) -> str:
        lines = [
            f"[{self.name}] {self.correct}/{self.total} = {self.accuracy:.1%}"
            + (f" (확신도 미달 {self.rejected}건)" if self.rejected else ""),
        ]
        for label in sorted(self.per_label):
            score = self.per_label[label]
            lines.append(f"  - {label:<16} {score.correct}/{score.support} = {score.accuracy:.1%}")
        for text, expected, predicted, confidence in self.errors[:10]:
            lines.append(f"  ! \"{text}\" → {predicted}({confidence:.2f}), 정답 {expected}")
        if len(self.errors) > 10:
            lines.append(f"  ... 오류 {len(self.errors) - 10}건 더")
        return "\n".join(lines)


def evaluate(model: NlModel, examples: list[TrainingExample], name: str) -> EvaluationReport:
    report = EvaluationReport(name=name)
    support: dict[str, int] = {}
    correct: dict[str, int] = {}

    for example in examples:
        prediction = model.classify(example.text)
        predicted = prediction.label if prediction else ""
        confidence = prediction.confidence if prediction else 0.0

        report.total += 1
        support[example.action] = support.get(example.action, 0) + 1

        accepted = prediction is not None and confidence >= model.min_confidence
        if not accepted:
            report.rejected += 1

        if accepted and predicted == example.action:
            report.correct += 1
            correct[example.action] = correct.get(example.action, 0) + 1
        else:
            report.errors.append((example.text, example.action, predicted or "(거절)", confidence))

    report.per_label = {
        label: LabelScore(label, count, correct.get(label, 0)) for label, count in support.items()
    }
    return report


def evaluate_out_of_domain(model: NlModel, utterances: list[str]) -> EvaluationReport:
    """캡슐 밖 발화는 __ood__ 라벨로 가거나 확신도 미달로 거절되어야 맞다."""
    report = EvaluationReport(name="out-of-domain")
    for utterance in utterances:
        prediction = model.classify(utterance)
        report.total += 1
        rejected = (
            prediction is None
            or prediction.label == OOD_LABEL
            or prediction.confidence < model.min_confidence
        )
        if rejected:
            report.correct += 1
            report.rejected += 1
        else:
            report.errors.append((utterance, "(거절)", prediction.label, prediction.confidence))
    return report


def extract_concepts(text: str, gazetteers: dict[str, dict[str, list[str]]]) -> dict[str, str]:
    """앱의 `ConceptExtractor`와 같은 방식으로 concept을 뽑는다. 평가용.

    점수도 앱과 맞춘다 — 정확히 포함되면 길이×2, 자모 거리 1까지 닮았으면 길이.
    퍼지 매칭이 있어야 "옵션 → 옵셔"처럼 받침이 떨어진 오인식을 앱과 똑같이 잡아낸다.
    """
    normalized = normalize(text)
    found: dict[str, str] = {}

    for concept_id, values in gazetteers.items():
        best_value = ""
        best_score = 0
        for value, surfaces in values.items():
            for surface in surfaces:
                candidate = normalize(surface)
                if not candidate:
                    continue

                if candidate in normalized:
                    score = len(candidate) * 2
                elif len(candidate) >= 2 and fuzzy_contains(normalized, candidate, 1):
                    score = len(candidate)
                else:
                    continue

                if score > best_score:
                    best_value, best_score = value, score
        if best_value:
            found[concept_id] = best_value

    count = _extract_count(normalized)
    if count:
        found["QuestionCount"] = count
    dots = _extract_dots(normalized)
    if dots:
        found["DotNumber"] = dots
    return found


def _extract_count(normalized: str) -> str | None:
    match = _DIGIT_COUNT.search(normalized)
    if match:
        return match.group(1)
    for word, value in _KOREAN_NUMBERS:
        for unit in _COUNT_UNITS:
            if word + unit in normalized:
                return value
    return None


def _extract_dots(normalized: str) -> str | None:
    """"3번 점", "135번", "삼번", "점 전부" → "1,3,5" 또는 "all". 점 번호는 한 자리라 숫자열은 자리마다 끊는다."""
    if _DOT_ALL.search(normalized):
        return "all"
    dots: set[int] = set()
    for match in _DOT_NUMBER.finditer(_DOT_NOISE.sub("", normalized)):
        token = match.group(1)
        numbers = [int(ch) for ch in token] if token[0].isdigit() else [_KOREAN_DIGITS[token]]
        if all(1 <= n <= 6 for n in numbers):
            dots.update(numbers)
    return ",".join(str(d) for d in sorted(dots)) if dots else None


def evaluate_concepts(
    examples: list[TrainingExample],
    gazetteers: dict[str, dict[str, list[str]]],
    name: str = "concept",
) -> EvaluationReport:
    """발화에 심어 둔 concept 값을 사전 매칭으로 되찾을 수 있는지."""
    report = EvaluationReport(name=name)
    for example in examples:
        if not example.concepts:
            continue
        found = extract_concepts(example.text, gazetteers)
        for concept_id, expected in example.concepts.items():
            report.total += 1
            actual = found.get(concept_id, "")
            if actual == expected:
                report.correct += 1
            else:
                report.errors.append((example.text, f"{concept_id}={expected}", actual or "(없음)", 0.0))
    return report
