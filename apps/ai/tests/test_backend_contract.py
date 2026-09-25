"""백엔드 FastApiClient 계약 테스트 — Java DTO 와 필드·형식이 일치하는지.

RegisterProblemRequest(targetText) → ProblemAnalyzeResponse(level, cells, skillSets[{skillType, metadata}])
ReviewAnalyzeRequest(skillStates[]) → ReviewAnalyzeResponse(learningSkillIds[])
"""

from fastapi.testclient import TestClient

from app.infrastructure.rule_engine import get_skill_catalog
from app.main import app

client = TestClient(app)


def test_analyze_gabang_returns_level_cells_skill_sets():
    res = client.post("/api/v1/analyze", json={"targetText": "가방"})
    assert res.status_code == 200
    body = res.json()
    assert body["level"] == "CONTRACTION"  # 약자 가·바 포함 → 약자 단계
    assert body["cells"] == [43, 24, 54]  # 약자 가(1246) 약자 바(45) 받침 ㅇ(2356)
    assert body["skillSets"] == [
        {"skillType": "SKILL_13", "metadata": "가"},
        {"skillType": "SKILL_13", "metadata": "바"},
        {"skillType": "SKILL_03", "metadata": "ㅇ"},
    ]
    assert set(body) == {"level", "cells", "skillSets"}  # 백엔드 record 컴포넌트 외 필드 없음


def test_analyze_includes_zero_width_rule_skill():
    body = client.post("/api/v1/analyze", json={"targetText": "음식"}).json()
    assert body["cells"] == [42, 34, 32, 21, 1]
    assert {"skillType": "SKILL_01", "metadata": "ㅇ생략"} in body[
        "skillSets"
    ]  # 첫소리 ㅇ 생략 규칙 요소
    assert body["level"] == "VOWEL"  # 첫소리 ㅇ 생략 규칙은 모음 단계 소속 → 모음 단계 문항


def test_analyze_skill_sets_match_catalog_metadata():
    """(skillType, metadata) 가 learning_skills 시드와 글자 단위로 같아야 백엔드가 요소를 찾는다."""
    cat = get_skill_catalog()
    keys = {(s.skill_type, s.metadata) for s in cat.all()}
    for word in ["학교", "그래서", "나이", "소화액", "까치"]:
        for ss in client.post("/api/v1/analyze", json={"targetText": word}).json()["skillSets"]:
            assert (ss["skillType"], ss["metadata"]) in keys, (word, ss)


def test_analyze_rejects_non_hangul():
    res = client.post("/api/v1/analyze", json={"targetText": "abc"})
    assert res.status_code == 422


def test_review_returns_weak_skills_only():
    states = [
        {
            "learningSkillId": 23,
            "nAttempts": 12,
            "nCorrect": 4,
            "lastPracticedAt": "2026-09-18T10:20:00",
            "masteryProb": 0.31,
            "errorCounts": {"dot_missing": 1, "dot_extra": 0, "mirror": 3},
        },
        {
            "learningSkillId": 16,
            "nAttempts": 8,
            "nCorrect": 2,
            "lastPracticedAt": "2026-09-20T10:20:00",
            "masteryProb": 0.4,
            "errorCounts": {},
        },
        {
            "learningSkillId": 1,
            "nAttempts": 20,
            "nCorrect": 19,
            "lastPracticedAt": "2026-09-22T10:20:00",
            "masteryProb": 0.97,
            "errorCounts": {},
        },
    ]
    res = client.post("/api/v1/reviewAnalyze", json={"skillStates": states})
    assert res.status_code == 200
    body = res.json()
    assert set(body) == {"learningSkillIds"}  # level 없음 — 문항 풀은 Spring 이 사용자 레벨로 결정
    assert set(body["learningSkillIds"][:2]) == {23, 16}
    assert 1 not in body["learningSkillIds"][:2]  # 숙련(0.97)은 앞에 오지 않는다


def test_review_with_empty_states_still_returns_valid_response():
    body = client.post("/api/v1/reviewAnalyze", json={"skillStates": []}).json()
    assert len(body["learningSkillIds"]) >= 1


def test_review_ignores_unknown_skill_ids_and_missing_optional_fields():
    states = [{"learningSkillId": 9999}, {"learningSkillId": 66}]
    body = client.post("/api/v1/reviewAnalyze", json={"skillStates": states}).json()
    assert 9999 not in body["learningSkillIds"]
    assert body["learningSkillIds"] == [66]
