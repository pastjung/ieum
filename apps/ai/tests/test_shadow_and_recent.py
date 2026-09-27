"""recentResults(6번)와 그림자 모델(7번)."""

import json
import logging

from fastapi.testclient import TestClient

from app.application.session_services import SHADOW_MODEL_PATH, load_shadow_predictor
from app.infrastructure.braille_core.shadow_model import FEATURE_NAMES, JsonTreeModel, features_for
from app.main import app

client = TestClient(app)
P = "/api/v1"


def _attempt(text, expected, inputs, states):
    return client.post(
        f"{P}/attempt-analyze",
        json={
            "attempt": {"targetText": text, "expectedPatterns": expected, "inputPatterns": inputs},
            "skillStates": states,
        },
    ).json()


def test_recent_results_is_appended_and_capped_at_five():
    st = [
        {
            "learningSkillId": 23,
            "nAttempts": 12,
            "nCorrect": 4,
            "masteryProb": 0.31,
            "recentResults": "01101",
        }
    ]
    body = _attempt("가방", [43, 24, 54], [43, 24, 32], st)  # 받침 ㅇ 틀림
    by = {u["learningSkillId"]: u["recentResults"] for u in body["updatedSkillStates"]}
    assert by[23] == "11010"  # 앞이 밀리고 이번 결과 '0'이 뒤에 붙음
    assert by[66] == "1" and by[70] == "1"  # 처음 만난 요소


def test_recent_results_optional_in_request():
    st = [{"learningSkillId": 23, "nAttempts": 1, "nCorrect": 1, "masteryProb": 0.5}]
    body = _attempt("가방", [43, 24, 54], [43, 24, 54], st)
    assert {u["learningSkillId"]: u["recentResults"] for u in body["updatedSkillStates"]}[23] == "1"


def test_recent_results_rejects_bad_format():
    st = [
        {
            "learningSkillId": 23,
            "nAttempts": 1,
            "nCorrect": 1,
            "masteryProb": 0.5,
            "recentResults": "abc",
        }
    ]
    res = client.post(
        f"{P}/attempt-analyze",
        json={
            "attempt": {
                "targetText": "가방",
                "expectedPatterns": [43, 24, 54],
                "inputPatterns": [43, 24, 54],
            },
            "skillStates": st,
        },
    )
    assert res.status_code == 422


def test_shadow_model_loads_and_predicts_probabilities():
    model = JsonTreeModel.load(SHADOW_MODEL_PATH)
    assert model.version == "lgbm-shadow-v1"
    from datetime import datetime

    now = datetime(2026, 9, 26, 9)
    strong = {
        "learning_skill_id": 1,
        "n_attempts": 20,
        "n_correct": 19,
        "last_practiced_at": now,
        "mastery_prob": 0.97,
        "error_counts": {},
        "recent_results": "11111",
    }
    weak = {
        "learning_skill_id": 16,
        "n_attempts": 10,
        "n_correct": 2,
        "last_practiced_at": now,
        "mastery_prob": 0.2,
        "error_counts": {"mirror": 4},
        "recent_results": "00100",
    }
    x_strong = features_for(1, "CONSONANT", strong, [strong, weak], [1, 16], 2, False, now)
    x_weak = features_for(16, "CONSONANT", weak, [strong, weak], [1, 16], 2, False, now)
    assert len(x_strong) == len(FEATURE_NAMES)
    p_strong, p_weak = model.predict(x_strong), model.predict(x_weak)
    assert 0.0 <= p_weak < p_strong <= 1.0


def test_attempt_analyze_emits_shadow_log_without_changing_response(caplog):
    assert load_shadow_predictor() is not None
    st = [
        {
            "learningSkillId": 23,
            "nAttempts": 12,
            "nCorrect": 4,
            "lastPracticedAt": "2026-09-18T10:20:00",
            "masteryProb": 0.31,
            "errorCounts": {"mirror": 3},
            "recentResults": "0110",
        }
    ]
    with caplog.at_level(logging.INFO, logger="itda.shadow"):
        body = _attempt("가방", [43, 24, 54], [43, 24, 32], st)
    assert set(body) == {
        "feedback",
        "skillResults",
        "updatedSkillStates",
        "cellSkills",
        "feedbackSteps",
    }  # 응답 형태 그대로
    lines = [r.getMessage() for r in caplog.records if "shadow_prediction" in r.getMessage()]
    assert len(lines) == 1
    rec = json.loads(lines[0])
    assert rec["model"] == "lgbm-shadow-v1" and rec["text"] == "가방"
    by = {s["skill"]: s for s in rec["skills"]}
    assert by[23]["correct"] is False and 0 <= by[23]["p_lgbm"] <= 1 and 0 <= by[23]["p_bkt"] <= 1
    assert set(by) == {66, 70, 23}
