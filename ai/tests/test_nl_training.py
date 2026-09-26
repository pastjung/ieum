"""캡슐 NL 트레이닝 파이프라인 테스트.

정확도 기준선을 테스트로 박아 두면, 발화 템플릿이나 사전을 고쳤을 때
말귀가 나빠지는 것을 바로 잡아낼 수 있다.
"""

import json

import pytest

from app.application.nl_training_service import NlTrainingService
from app.domain.nl import features
from app.domain.nl.evaluator import evaluate, evaluate_concepts, extract_concepts
from app.domain.nl.generator import GOLD_EXAMPLES, OUT_OF_DOMAIN, generate
from app.domain.nl.model import NlModel
from app.domain.nl.schema import LABELS, OOD_LABEL, concept_gazetteers
from app.infrastructure.nl_model_store import JsonNlModelStore


@pytest.fixture(scope="module")
def outcome():
    return NlTrainingService(store=None).run(export=False)


# --- 피처 (앱과 정의가 같아야 한다) -------------------------------------------

def test_normalize_drops_spaces_and_punctuation():
    assert features.normalize("복습, 열어 줘!") == "복습열어줘"


def test_jamo_decomposition():
    assert features.to_jamo("잇다") == "ㅇㅣㅅㄷㅏ"


def test_features_include_char_and_jamo_ngrams():
    extracted = features.extract("복습")
    assert "c2:복습" in extracted
    assert any(feature.startswith("j3:") for feature in extracted)


# --- 발화 생성 ----------------------------------------------------------------

def test_generates_examples_for_every_action():
    examples = generate()
    labels = {example.action for example in examples}
    assert labels == set(LABELS) | {OOD_LABEL}
    for label in labels:
        assert sum(1 for e in examples if e.action == label) >= 5


def test_generated_examples_carry_concept_annotations():
    examples = generate()
    with_screen = [e for e in examples if "ScreenName" in e.concepts]
    assert with_screen, "ScreenName concept이 붙은 발화가 없다"
    for example in with_screen[:20]:
        found = extract_concepts(example.text, concept_gazetteers())
        assert found.get("ScreenName") == example.concepts["ScreenName"], example.text


def test_generation_is_deterministic():
    assert [e.text for e in generate(seed=7)] == [e.text for e in generate(seed=7)]


# --- 학습·평가 ----------------------------------------------------------------

def test_holdout_accuracy(outcome):
    report = next(r for r in outcome.reports if r.name == "holdout")
    assert report.accuracy >= 0.90, report.summary()


def test_gold_accuracy(outcome):
    report = next(r for r in outcome.reports if r.name.startswith("gold"))
    assert report.accuracy >= 0.75, report.summary()


def test_out_of_domain_is_rejected(outcome):
    report = next(r for r in outcome.reports if r.name == "out-of-domain")
    assert report.accuracy >= 0.66, report.summary()


def test_concepts_extracted_from_gold(outcome):
    report = evaluate_concepts(GOLD_EXAMPLES, outcome.model.concepts)
    assert report.accuracy >= 0.80, report.summary()


def test_every_label_is_learned(outcome):
    assert set(outcome.model.labels) == set(LABELS) | {OOD_LABEL}


def test_confidence_is_between_zero_and_one(outcome):
    for utterance in ["복습 시작해줘", *OUT_OF_DOMAIN]:
        prediction = outcome.model.classify(utterance)
        assert prediction is not None
        assert 0.0 < prediction.confidence <= 1.0


# --- 내보내기 -----------------------------------------------------------------

def test_model_survives_json_round_trip(outcome):
    payload = json.dumps(outcome.model.to_dict(), ensure_ascii=False)
    restored = NlModel.from_dict(json.loads(payload))

    assert restored.labels == outcome.model.labels
    for utterance in ("복습 시작해줘", "설정 열어줘", "뒤로"):
        assert restored.classify(utterance).label == outcome.model.classify(utterance).label


def test_store_writes_and_reads_back(tmp_path, outcome):
    target = tmp_path / "nl" / "model.json"
    store = JsonNlModelStore(targets=[target])

    written = store.save(outcome.model)
    assert written == [target]
    assert target.exists()

    restored = store.load()
    assert restored is not None
    assert restored.labels == outcome.model.labels


def test_exported_json_has_keys_the_android_app_reads(outcome):
    data = outcome.model.to_dict()
    for key in ("version", "trainedAt", "labels", "logPrior", "logLikelihood",
                "defaultLogLikelihood", "minConfidence", "concepts"):
        assert key in data, f"앱이 읽는 키가 빠졌다: {key}"


# --- 평가기 자체 --------------------------------------------------------------

def test_evaluate_counts_rejections(outcome):
    report = evaluate(outcome.model, GOLD_EXAMPLES, "gold-recheck")
    assert report.total == len(GOLD_EXAMPLES)
    assert report.correct + len(report.errors) == report.total
