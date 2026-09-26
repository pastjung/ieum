"""POST /api/v1/skill-states — 글자 없이 숙련도만 갱신한다.

자음·모음 단계 문항은 'ㄱ'·'ㅙ' 같은 낱자라 점역기가 분해하지 못한다(non-Hangul).
그래서 Spring이 그 문항을 직접 채점하는데, 숙련도(BKT)는 여기에만 있어 갱신할 수 없었다.
그 결과 자음·모음은 아무리 틀려도 약한 요소로 뽑히지 않아 복습에 영영 나오지 않았다.

이 엔드포인트는 채점 결과(요소별 정오)만 받아 상태를 갱신한다 — 점역을 거치지 않는다.
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

SKILL_ID = 23


def _update(results, states=None):
    return client.post(
        "/api/v1/skill-states",
        json={"skillStates": states or [], "results": results},
    )


def test_기록이_없던_요소도_갱신된다():
    res = _update(
        [{"learningSkillId": SKILL_ID, "correct": False, "errorCounts": {"dot_missing": 1}}]
    )

    assert res.status_code == 200
    updated = res.json()["updatedSkillStates"]
    assert len(updated) == 1
    s = updated[0]
    assert s["learningSkillId"] == SKILL_ID
    assert s["nAttempts"] == 1
    assert s["nCorrect"] == 0
    assert s["errorCounts"] == {"dot_missing": 1}
    assert s["masteryProb"] is not None
    assert s["lastPracticedAt"] is not None


def test_맞히면_정답_횟수가_오르고_숙련도가_올라간다():
    wrong = _update([{"learningSkillId": SKILL_ID, "correct": False, "errorCounts": {}}]).json()[
        "updatedSkillStates"
    ][0]
    right = _update([{"learningSkillId": SKILL_ID, "correct": True, "errorCounts": {}}]).json()[
        "updatedSkillStates"
    ][0]

    assert right["nCorrect"] == 1 and wrong["nCorrect"] == 0
    assert right["masteryProb"] > wrong["masteryProb"]


def test_기존_상태에_누적된다():
    prior = [
        {
            "learningSkillId": SKILL_ID,
            "nAttempts": 4,
            "nCorrect": 3,
            "lastPracticedAt": "2026-09-20T00:00:00",
            "masteryProb": 0.62,
            "errorCounts": {"dot_missing": 2},
        }
    ]

    s = _update(
        [{"learningSkillId": SKILL_ID, "correct": False, "errorCounts": {"mirror": 1}}], prior
    ).json()["updatedSkillStates"][0]

    assert s["nAttempts"] == 5
    assert s["nCorrect"] == 3
    # 오류 유형은 기존 것에 더해진다 — 덮어쓰면 누적 이력이 사라진다
    assert s["errorCounts"] == {"dot_missing": 2, "mirror": 1}


def test_여러_요소를_한_번에_갱신한다():
    res = _update(
        [
            {"learningSkillId": 1, "correct": True, "errorCounts": {}},
            {"learningSkillId": 2, "correct": False, "errorCounts": {"dot_extra": 1}},
        ]
    )

    updated = res.json()["updatedSkillStates"]
    assert {s["learningSkillId"] for s in updated} == {1, 2}


def test_결과가_비면_갱신본도_비어_있다():
    res = _update([])

    assert res.status_code == 200
    assert res.json()["updatedSkillStates"] == []


def test_점역을_거치지_않는다():
    # 낱자든 무엇이든 글자를 받지 않는다. 받으면 같은 벽(non-Hangul)에 다시 부딪힌다
    res = _update([{"learningSkillId": SKILL_ID, "correct": False, "errorCounts": {}}])

    assert res.status_code == 200
