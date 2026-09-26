"""낱자·드릴 문항('ㄱ', 'ㅙ', '초성 ㄱ', '받침 ㄱ', '모음 ㅏ', '약자 가', '약어 그래서', '음절 아') 채점.

자음·모음 단계 문항은 어절이 아니라 낱자라 점역기가 분해하지 못했고, 그래서 Spring 이 따로 채점하고
/skill-states 로 숙련도만 갱신해야 했다. 이제 /attempt-analyze 와 /session-summary 가 직접 받는다.
"""

from fastapi.testclient import TestClient

from app.infrastructure.braille_core.drill_items import build_drill_item, is_drill_text
from app.main import app

client = TestClient(app)
P = "/api/v1"


def _item(text, expected=None):
    item = build_drill_item(text, expected)
    assert item is not None, text
    return item


def _attempt(text, expected, inputs):
    return client.post(
        f"{P}/attempt-analyze",
        json={
            "attempt": {"targetText": text, "expectedPatterns": expected, "inputPatterns": inputs},
            "skillStates": [],
        },
    )


def test_bare_consonant_is_onset_or_coda_by_expected_patterns():
    assert _item("ㄱ", [8])["kc"] == ["초성:ㄱ"] and _item("ㄱ", [8])["cells"] == [[4]]
    assert _item("ㄱ", [1])["kc"] == ["종성:ㄱ"] and _item("ㄱ", [1])["cells"] == [[1]]
    assert _item("ㄱ")["kc"] == ["초성:ㄱ"]  # 정답 정보가 없으면 첫소리
    assert _item("ㅇ", [54])["kc"] == ["종성:ㅇ"]  # 첫소리 ㅇ은 셀이 없으니 받침만 가능


def test_vowel_double_consonant_and_prefixed_forms():
    assert _item("ㅙ")["kc"] == ["중성:ㅙ", "중성:ㅙ"]  # 두 칸 모음
    assert _item("ㄲ")["kc"] == ["규칙:된소리표", "초성:ㄱ"]
    assert _item("ㄳ", [1, 3])["kc"] == ["종성:ㄳ", "종성:ㄳ"]
    assert _item("초성 ㄴ")["cells"] == [[1, 4]]
    assert _item("받침 ㄴ")["cells"] == [[2, 5]]
    assert _item("모음 ㅏ")["cells"] == [[1, 2, 6]]
    assert _item("약자 가")["kc"] == ["약자:ㄱㅏ"]
    assert _item("약어 그래서")["kc"] == ["약어:그래서", "약어:그래서"]
    assert build_drill_item("가방") is None and not is_drill_text("가방")


def test_attempt_analyze_grades_bare_consonant_with_error_type():
    body = _attempt("ㄱ", [1], [8]).json()  # 받침 ㄱ(1점) 자리에 첫소리 ㄱ(4점)
    assert body["skillResults"] == [
        {"learningSkillId": 16, "correct": False, "errorCounts": {"mirror": 1}}
    ]
    assert "받침 기역" in body["feedback"]["tts"]
    ok = _attempt("ㄱ", [8], [8]).json()
    assert ok["skillResults"] == [{"learningSkillId": 1, "correct": True, "errorCounts": {}}]
    assert (
        ok["updatedSkillStates"][0]["learningSkillId"] == 1
        and ok["updatedSkillStates"][0]["nCorrect"] == 1
    )


def test_attempt_analyze_grades_vowel_and_silent_onset_drill():
    body = _attempt("ㅙ", [39, 23], [39, 23]).json()  # ㅙ = ㅗ(1,2,3,6)ㅐ(1,2,3,5) 두 칸
    assert body["skillResults"] == [{"learningSkillId": 58, "correct": True, "errorCounts": {}}]
    six = _attempt("음절 아", [35], [54, 35]).json()  # 첫소리 ㅇ을 적음
    rule = next(r for r in six["skillResults"] if r["learningSkillId"] == 14)
    assert rule["correct"] is False and rule["errorCounts"] == {"silent_onset_written": 1}
    assert "'아'는" in six["feedback"]["tts"]


def test_session_summary_accepts_mixed_drill_and_word_items():
    req = {
        "attempts": [
            {"targetText": "ㄱ", "expectedPatterns": [8], "inputPatterns": [1]},
            {"targetText": "ㅏ", "expectedPatterns": [35], "inputPatterns": [35]},
            {"targetText": "가방", "expectedPatterns": [43, 24, 54], "inputPatterns": [43, 24, 54]},
        ],
        "skillStates": [],
    }
    res = client.post(f"{P}/session-summary", json=req)
    assert res.status_code == 200
    body = res.json()
    assert body["summary"].startswith("3문제 중 2문제")
    assert body["errorTypeCounts"] == {"mirror": 1}


def test_non_hangul_still_rejected():
    assert _attempt("abc", [], [1]).status_code == 422


def test_double_final_consonants_by_article_4():
    """제4항 쌍받침: ㅆ = 3·4점 한 칸, ㄲ = 받침 ㄱ 두 번. 낱자로 오면 expectedPatterns 로 첫소리(된소리)와 가른다."""
    assert _item("ㅆ", [12])["kc"] == ["종성:ㅆ"] and _item("ㅆ", [12])["cells"] == [[3, 4]]
    assert _item("ㄲ", [1, 1])["kc"] == ["종성:ㄲ", "종성:ㄲ"]
    assert _item("ㅆ", [32, 32])["kc"] == [
        "규칙:된소리표",
        "초성:ㅅ",
    ]  # 정답이 된소리 첫소리면 그쪽
    assert _item("받침 ㅆ")["cells"] == [[3, 4]] and _item("받침 ㄲ")["cells"] == [[1], [1]]
    ok = _attempt("ㅆ", [12], [12]).json()
    assert ok["skillResults"] == [{"learningSkillId": 31, "correct": True, "errorCounts": {}}]
    wrong = _attempt("ㅆ", [12], [32]).json()  # 6점 하나 (된소리표 모양)
    assert (
        wrong["skillResults"][0]["learningSkillId"] == 31
        and wrong["skillResults"][0]["correct"] is False
    )
    assert "받침 쌍시옷" in wrong["feedback"]["tts"]


def test_every_seeded_drill_in_db_is_graded_with_matching_skills():
    """DB 시드의 드릴 78개(자음·모음·약자·음절) 전부: 200 + 요소 id 가 problem_skills 와 같음 + 정답 입력은 전부 맞음."""
    import json
    from pathlib import Path

    drills = json.loads(
        (Path(__file__).parent / "fixtures_seed_drills.json").read_text(encoding="utf-8")
    )
    assert len(drills) >= 70
    for d in drills:
        res = _attempt(d["text"], d["expectedPatterns"], d["expectedPatterns"])
        assert res.status_code == 200, (d["text"], res.text)
        body = res.json()
        assert sorted(s["learningSkillId"] for s in body["skillResults"]) == d["skillIds"], d[
            "text"
        ]
        assert all(s["correct"] for s in body["skillResults"]), d["text"]
