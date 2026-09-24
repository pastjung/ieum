"""① /attempt-analyze, ② /session-summary 계약 테스트 (2026-09-25 확정안)."""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
STATES = [
    {
        "learningSkillId": 23,
        "nAttempts": 12,
        "nCorrect": 4,
        "lastPracticedAt": "2026-09-18T10:20:00",
        "masteryProb": 0.31,
        "errorCounts": {"mirror": 3, "dot_missing": 1},
    },
    {
        "learningSkillId": 16,
        "nAttempts": 9,
        "nCorrect": 3,
        "lastPracticedAt": "2026-09-25T09:30:45",
        "masteryProb": 0.1494,
        "errorCounts": {"mirror": 3},
    },
    {
        "learningSkillId": 1,
        "nAttempts": 21,
        "nCorrect": 20,
        "lastPracticedAt": "2026-09-25T09:30:45",
        "masteryProb": 0.9898,
        "errorCounts": {"dot_missing": 1},
    },
]


def _attempt(text, expected, inputs, states=STATES):
    return client.post(
        "/attempt-analyze",
        json={
            "attempt": {"targetText": text, "expectedPatterns": expected, "inputPatterns": inputs},
            "skillStates": states,
        },
    )


def test_attempt_wrong_cell_gives_feedback_skill_results_and_changed_states_only():
    res = _attempt("가방", [43, 24, 54], [43, 24, 32])
    assert res.status_code == 200
    body = res.json()
    assert set(body) == {"feedback", "skillResults", "updatedSkillStates"}
    assert "세 번째 칸" in body["feedback"]["tts"] and "받침 이응" in body["feedback"]["tts"]
    assert body["feedback"]["detail"]
    results = {r["learningSkillId"]: r for r in body["skillResults"]}
    assert results[66] == {"learningSkillId": 66, "correct": True, "errorCounts": {}}
    assert results[70]["correct"] is True and results[70]["errorCounts"] == {}
    assert results[23]["correct"] is False and results[23]["errorCounts"] == {"dot_missing": 1}
    updated = {s["learningSkillId"]: s for s in body["updatedSkillStates"]}
    assert set(updated) == {23, 66, 70}  # 변경분만 — 안 건드린 16, 1 은 없음
    assert updated[23]["nAttempts"] == 13 and updated[23]["nCorrect"] == 4
    assert updated[23]["errorCounts"] == {"mirror": 3, "dot_missing": 2}
    assert updated[23]["masteryProb"] < 0.31  # 6일 망각 + 오답
    assert updated[66]["nAttempts"] == 1 and updated[66]["nCorrect"] == 1  # 처음 만난 요소 → 새 행
    assert "Z" not in updated[23]["lastPracticedAt"]  # LocalDateTime 형식


def test_attempt_correct_answer_marks_all_skills_correct():
    body = _attempt("사과", [7, 8, 39], [7, 8, 39]).json()
    assert body["feedback"]["tts"].startswith("정답")
    assert all(r["correct"] and r["errorCounts"] == {} for r in body["skillResults"])
    assert all(s["nCorrect"] >= 1 for s in body["updatedSkillStates"])


def test_attempt_free_length_input_silent_onset_rule():
    body = _attempt("음식", [42, 34, 32, 21, 1], [54, 42, 34, 32, 21, 1]).json()  # 6칸 입력
    assert "이응" in body["feedback"]["tts"]
    rule = next(r for r in body["skillResults"] if r["learningSkillId"] == 14)
    assert rule["correct"] is False and rule["errorCounts"] == {"silent_onset_written": 1}
    assert all(
        r["correct"] for r in body["skillResults"] if r["learningSkillId"] != 14
    )  # 나머지 칸은 맞음


def test_attempt_rejects_bad_cells_and_non_hangul():
    assert _attempt("가방", [43, 24, 54], [43, 24, 99]).status_code == 400
    assert _attempt("abc", [], [1]).status_code == 422


def test_session_summary_is_read_only_and_ranks_weak_skills():
    first = _attempt("가방", [43, 24, 54], [43, 24, 32]).json()["updatedSkillStates"]
    states = first + [s for s in STATES if s["learningSkillId"] != 23]
    req = {
        "attempts": [
            {
                "targetText": "학교",
                "expectedPatterns": [26, 1, 8, 44],
                "inputPatterns": [26, 8, 8, 44],
            },
            {"targetText": "나무", "expectedPatterns": [9, 17, 13], "inputPatterns": [9, 17, 13]},
            {"targetText": "가방", "expectedPatterns": [43, 24, 54], "inputPatterns": [43, 24, 32]},
        ],
        "skillStates": states,
    }
    res = client.post("/session-summary", json=req)
    assert res.status_code == 200
    body = res.json()
    assert set(body) == {"summary", "ttsText", "weakSkills"}
    assert body["summary"].startswith("3문제 중 1문제")
    ids = [w["learningSkillId"] for w in body["weakSkills"]]
    assert ids[:2] == [23, 16]  # 약한 순
    assert all(w["label"] in {"더 연습해요", "배우는 중"} for w in body["weakSkills"])
    assert 1 not in ids  # 초성 ㄱ(0.99)은 익숙해요 → 제외
    assert (
        client.post("/session-summary", json=req).json() == body
    )  # 두 번 불러도 같다(상태 변화 없음)
