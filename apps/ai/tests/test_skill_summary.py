"""POST /api/v1/skill-summary — 세션 밖에서 요소별 숙련 상태를 읽는다.

"내 실력" 화면과 홈의 복습 카드가 "무엇이 약한지"를 보여주려면 세션과 무관하게 숙련
상태를 알아야 한다. 지금까지 그 값은 세션 완료 응답(weakSkills)에만 실려서, 두 화면은
고정값을 내 실력처럼 보여주고 있었다.

숙련 라벨의 기준(label_of)은 여기에만 있다. Spring 이 문턱값을 베끼면 두 곳이 갈린다.
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def _summary(states):
    return client.post("/api/v1/skill-summary", json={"skillStates": states})


def _state(skill_id, *, attempts=4, correct=3, mastery=0.62):
    return {
        "learningSkillId": skill_id,
        "nAttempts": attempts,
        "nCorrect": correct,
        "lastPracticedAt": "2026-09-25T00:00:00",
        "masteryProb": mastery,
        "errorCounts": {},
    }


def test_요소마다_숙련도와_라벨을_돌려준다():
    res = _summary([_state(23)])

    assert res.status_code == 200
    skills = res.json()["skills"]
    assert len(skills) == 1
    assert skills[0]["learningSkillId"] == 23
    assert 0.0 <= skills[0]["masteryProb"] <= 1.0
    assert skills[0]["label"]


def test_숙련도가_높으면_익숙하다고_한다():
    high = _summary([_state(1, mastery=0.98)]).json()["skills"][0]
    low = _summary([_state(1, mastery=0.05)]).json()["skills"][0]

    assert high["label"] != low["label"]
    assert high["masteryProb"] > low["masteryProb"]


def test_약한_것부터_정렬한다():
    # 화면이 "더 연습할 것"을 먼저 보여준다. 정렬을 앱이 하면 두 화면이 서로 다른 순서를 쓴다
    res = _summary([_state(1, mastery=0.9), _state(2, mastery=0.2), _state(3, mastery=0.5)])

    ids = [s["learningSkillId"] for s in res.json()["skills"]]
    assert ids == [2, 3, 1]


def test_기록이_없으면_빈_목록이다():
    res = _summary([])

    assert res.status_code == 200
    assert res.json()["skills"] == []


def test_숙련도가_비어_있어도_기본값으로_판정한다():
    # 낱자 문항만 풀어 mastery_prob 가 아직 없는 요소가 있을 수 있다
    res = _summary([{"learningSkillId": 7, "nAttempts": 1, "nCorrect": 0}])

    assert res.status_code == 200
    assert res.json()["skills"][0]["masteryProb"] is not None
