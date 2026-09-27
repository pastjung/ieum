"""① 모델링 — 캡슐이 이해할 액션(발화 함수)과 concept(발화 변수) 정의.

앱 쪽 `front/packages/itda/src/capsule/model.ts`와 짝이다. **ScreenName 값은 그쪽
`DESTINATIONS`의 id와 정확히 같아야 한다** — 모델이 뱉은 id로 앱이 화면을 찾기 때문이다.
여기 정의가 학습 발화 생성의 재료가 되고, concept 사전은 모델 JSON에 함께 실려
앱이 같은 어휘를 쓰게 한다.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ConceptDef:
    """발화 변수. values는 {정규화 값: [그 값을 가리키는 표현들]}."""

    id: str
    description: str
    values: dict[str, list[str]]


@dataclass(frozen=True)
class ActionDef:
    """발화 함수. required가 비면 되묻지 않고 바로 실행한다."""

    id: str
    goal: str
    required: tuple[str, ...] = ()
    optional: tuple[str, ...] = ()


# --- concept 사전 -------------------------------------------------------------

SCREEN_VALUES: dict[str, list[str]] = {
    "home": ["홈", "메인", "첫 화면", "처음 화면", "시작 화면", "홈 화면"],
    "practice": ["학습", "점자 배우기", "배우러", "학습 탭", "배우기", "테마", "주제 고르기"],
    "lesson": ["수업", "실시간 수업", "수업 참여", "교실", "강의", "라이브"],
    # "6점"만 두면 "6번 점 켜줘"(RaiseDots)와 헷갈려 "문제"·"연습"까지 붙인다.
    "review": ["문제", "문제 풀기", "연습", "입력 연습", "복습", "오늘의 복습", "리뷰"],
    "wrong-note": ["오답", "오답 노트", "틀린 문제", "틀린 거", "오답 분석", "오답 복습"],
    "record": ["내 실력", "실력", "학습 기록", "기록", "통계", "리포트", "성적", "진도"],
    "dictionary": ["사전", "점자 사전", "낱말", "낱말 찾기", "찾아보기"],
    "device": ["손 위치", "손 맞추기", "위치 맞추기", "캘리브레이션"],
    "accessibility": ["소리와 진동", "접근성", "음성 안내", "진동 설정"],
    "settings": ["설정", "환경설정", "세팅", "옵션"],
    "voice": ["목소리", "내 목소리", "목소리 복제", "음성 복제", "보이스", "목소리 설정"],
}

LEVEL_VALUES: dict[str, list[str]] = {
    "L1": ["L1", "엘원", "1단계", "일단계", "받침 없는"],
    "L2": ["L2", "엘투", "2단계", "이단계", "받침"],
    "L3": ["L3", "엘쓰리", "3단계", "삼단계", "약자"],
    "L4": ["L4", "엘포", "4단계", "사단계", "긴 단어"],
}

PERIOD_VALUES: dict[str, list[str]] = {
    "today": ["오늘", "오늘의", "금일"],
    "week": ["이번 주", "이번주", "일주일", "최근 일주일"],
    "last_week": ["지난주", "지난 주", "저번 주"],
    "all": ["전체", "전부", "모든", "누적"],
}

CONCEPTS: list[ConceptDef] = [
    ConceptDef("ScreenName", "이동할 화면", SCREEN_VALUES),
    ConceptDef("BrailleLevel", "문제 난이도", LEVEL_VALUES),
    ConceptDef("Period", "기간", PERIOD_VALUES),
]

# LessonName은 서버에서 받아 오는 동적 어휘라 학습 사전에 넣지 않는다.
DYNAMIC_CONCEPTS = ("LessonName",)

# QuestionCount("열 문제"), DotNumber("3번 점", "1 3 5번")는 숫자 규칙으로 뽑아 사전이 없다.


# --- 액션 ---------------------------------------------------------------------

ACTIONS: list[ActionDef] = [
    ActionDef("OpenScreen", "말한 화면으로 이동", required=("ScreenName",)),
    ActionDef("StartReview", "오늘의 복습 시작", optional=("QuestionCount", "BrailleLevel")),
    ActionDef("ShowWrongNotes", "오답 모아 보기", optional=("Period", "BrailleLevel")),
    ActionDef("JoinLesson", "실시간 수업 참여", required=("LessonName",)),
    ActionDef("ShowProgress", "학습 기록 확인", optional=("Period",)),
    ActionDef("ConnectDevice", "촉각 기기 연결"),
    ActionDef("RaiseDots", "말한 번호의 점 올리기(솔레노이드 ON)", required=("DotNumber",)),
    ActionDef("LowerDots", "말한 번호의 점 내리기(없으면 전부)", optional=("DotNumber",)),
    ActionDef("GoBack", "이전 화면"),
    ActionDef("ShowHelp", "도움말"),
    ActionDef("Cancel", "취소"),
]

LABELS: list[str] = [action.id for action in ACTIONS]


def concept_gazetteers() -> dict[str, dict[str, list[str]]]:
    """모델 JSON에 실어 앱이 그대로 쓰게 할 concept 사전."""
    return {concept.id: concept.values for concept in CONCEPTS}


# 캡슐이 처리하지 않는 발화를 담는 라벨.
# 나이브 베이즈는 확신도가 늘 높게 나오므로, "모르는 말"도 하나의 라벨로 학습시켜야
# 캡슐 밖 요청을 실제로 거절할 수 있다. 앱에서는 이 라벨이 액션 없음으로 매핑된다.
OOD_LABEL = "__ood__"
