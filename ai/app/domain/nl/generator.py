"""④ 트레이닝 — 캡슐이 처리할 수 있는 발화를 만들어 낸다.

빅스비 NL Training이 "발화를 등록하고 concept 구간을 표시"하는 일을 하듯,
여기서는 템플릿에 concept 값을 채워 넣어 **라벨·concept이 붙은 학습 발화**를 자동 생성한다.

세 가지를 함께 만든다.
1. 템플릿 조합 — 액션 × concept 값
2. 말투 변형 — "~요", "좀 ~" 같은 흔한 변주
3. 오인식 변형 — 받침 탈락, ㅅ/ㅆ 혼동, 띄어쓰기 붕괴 (음성 인식이 실제로 내는 오류)

평가는 템플릿에서 만든 발화가 아니라 손으로 적은 [GOLD_EXAMPLES]로도 함께 재야
"템플릿을 외운 것"과 "일반화한 것"을 구분할 수 있다.
"""

from __future__ import annotations

import itertools
import random
from dataclasses import dataclass

from app.domain.nl.features import compose_syllable, decompose_syllable
from app.domain.nl.schema import LEVEL_VALUES, OOD_LABEL, PERIOD_VALUES, SCREEN_VALUES


@dataclass(frozen=True)
class TrainingExample:
    """학습 발화 하나. concepts는 {concept id: 정규화 값}."""

    text: str
    action: str
    concepts: dict[str, str]


# --- 슬롯 재료 ----------------------------------------------------------------

COUNT_CHOICES: list[tuple[str, str]] = [
    ("세 문제", "3"),
    ("다섯 문제", "5"),
    ("열 문제", "10"),
    ("열다섯 문제", "15"),
    ("스무 문제", "20"),
    ("3문제", "3"),
    ("5문제", "5"),
    ("10문제", "10"),
    ("5개", "5"),
    ("10개", "10"),
]

# 점 번호 슬롯. 값은 앱 DotNumbers 규약("1,3,5" / "all")과 같다.
DOT_CHOICES: list[tuple[str, str]] = [
    ("1번", "1"),
    ("2번", "2"),
    ("3번", "3"),
    ("4번", "4"),
    ("5번", "5"),
    ("6번", "6"),
    ("삼번", "3"),
    ("육번", "6"),
    ("1번 3번", "1,3"),
    ("2번 5번", "2,5"),
    ("1 3 5번", "1,3,5"),
    ("2 4 6번", "2,4,6"),
    ("1번 2번 3번", "1,2,3"),
]

ALL_DOTS_CHOICES: list[tuple[str, str]] = [
    ("전부", "all"),
    ("모두", "all"),
    ("다", "all"),
]

TEMPLATES: dict[str, list[str]] = {
    "OpenScreen": [
        "{screen} 열어줘",
        "{screen} 보여줘",
        "{screen} 화면 열어줘",
        "{screen}{ro} 가줘",
        "{screen}{ro} 이동해줘",
        "{screen} 띄워줘",
        "{screen} 화면으로 가자",
        "{screen} 켜줘",
    ],
    "StartReview": [
        "복습 시작해줘",
        "복습하자",
        "오늘의 복습 시작해줘",
        "복습 문제 내줘",
        "복습 시켜줘",
        "{count} 복습할래",
        "복습 {count} 풀래",
        "{level} 복습 시작해줘",
        "{level} 복습 문제 내줘",
        "{count} 풀래",
        "오늘 복습 골라줘",
    ],
    "ShowWrongNotes": [
        "오답 노트 보여줘",
        "틀린 문제 알려줘",
        "오답 정리해줘",
        "{period} 오답 보여줘",
        "{period} 틀린 거 알려줘",
        "{level} 오답 보여줘",
        "내가 자주 틀리는 거 알려줘",
        "오답 분석 보여줘",
    ],
    "JoinLesson": [
        "수업 들어가줘",
        "수업 참여할래",
        "실시간 수업 들어가자",
        "수업방 입장해줘",
        "지금 수업 접속해줘",
        "교실 들어가줘",
        "강의 참가할래",
    ],
    "ShowProgress": [
        "학습 기록 보여줘",
        "내 진도 어때",
        "성적 알려줘",
        "리포트 보여줘",
        "{period} 통계 알려줘",
        "{period} 학습 기록 보여줘",
        "얼마나 했는지 알려줘",
        "정답률 알려줘",
    ],
    "ConnectDevice": [
        "기기 연결해줘",
        "블루투스 연결해줘",
        "점자 기기 연결해줘",
        "촉각 모듈 붙여줘",
        "기기 상태 알려줘",
        "장치 검색해줘",
        "점자 단말기 연결해줘",
    ],
    "RaiseDots": [
        "{dot} 점 올려줘",
        "{dot} 올려줘",
        "{dot} 점 켜줘",
        "{dot} 켜줘",
        "{dot} 점 세워줘",
        "{dot} 점 올려",
        "점 {dot} 올려줘",
        "{dot} 점 켜",
        "솔레노이드 {dot} 켜줘",
        "점 {all} 올려줘",
        "점 {all} 켜줘",
        "모든 점 올려줘",
        "점 올려줘",
    ],
    "LowerDots": [
        "{dot} 점 내려줘",
        "{dot} 내려줘",
        "{dot} 점 꺼줘",
        "{dot} 꺼",
        "{dot} 점 내려",
        "점 {dot} 내려줘",
        "점 {all} 내려줘",
        "점 {all} 꺼줘",
        "{all} 내려줘",
        "모든 점 내려줘",
        "점 초기화해줘",
        "솔레노이드 {all} 꺼줘",
    ],
    "GoBack": [
        "뒤로",
        "뒤로 가줘",
        "이전 화면으로",
        "돌아가줘",
        "이전으로 가줘",
        "back",
    ],
    "ShowHelp": [
        "도움말",
        "뭐 할 수 있어",
        "뭘 할 수 있어",
        "명령어 알려줘",
        "사용법 알려줘",
        "어떻게 써",
        "기능 알려줘",
    ],
    "Cancel": [
        "취소",
        "그만",
        "됐어",
        "아니야",
        "취소해줘",
        "그만할래",
        "종료해줘",
        # "그만할래"가 "수업 참여할래"에 끌려가서, 같은 어미를 쓰는 예를 더 준다.
        "그만할게",
        "그만하자",
        "그만둘래",
        "안 할래",
        "취소할래",
        "관둘래",
        "없던 걸로 해줘",
        "아니 됐어",
    ],
}


# 캡슐 밖 발화(부정 예시). 이것도 하나의 라벨로 학습시켜야 "모르는 말"을 거절할 수 있다.
OOD_TEMPLATES: list[str] = [
    "오늘 날씨 어때",
    "내일 비 와",
    "음악 틀어줘",
    "다음 곡 넘겨줘",
    "엄마한테 전화 걸어줘",
    "문자 보내줘",
    "알람 맞춰줘",
    "타이머 오 분",
    "지금 몇 시야",
    "뉴스 읽어줘",
    "길 안내해줘",
    "가까운 편의점 어디야",
    "주변 약국 찾아줘",
    "농담 하나 해줘",
    "너 이름이 뭐야",
    "사진 찍어줘",
    "유튜브 재생해줘",
    "카톡 확인해줘",
    "버스 언제 와",
    "환율 알려줘",
    "영어로 번역해줘",
    "오늘 일정 뭐야",
    "배터리 얼마나 남았어",
    "볼륨 올려줘",
    "택시 불러줘",
    "라면 끓이는 법 알려줘",
]

# 템플릿에서 만들지 않은, 손으로 적은 평가용 발화. 일반화 성능을 재는 데 쓴다.
GOLD_EXAMPLES: list[TrainingExample] = [
    TrainingExample("오늘 복습 좀 시켜줘", "StartReview", {}),
    TrainingExample("복습 열 문제만 풀고 싶어", "StartReview", {"QuestionCount": "10"}),
    TrainingExample("약자 문제로 복습 시작", "StartReview", {"BrailleLevel": "L3"}),
    TrainingExample("이번 주에 틀린 거 정리해서 보여줘", "ShowWrongNotes", {"Period": "week"}),
    TrainingExample("오답 노트 좀 볼게", "ShowWrongNotes", {}),
    TrainingExample("실시간 수업 지금 들어갈래", "JoinLesson", {}),
    TrainingExample("수업방 접속해줘", "JoinLesson", {}),
    TrainingExample("내 학습 통계 어떻게 되지", "ShowProgress", {}),
    TrainingExample("지난주 진도 알려줘", "ShowProgress", {"Period": "last_week"}),
    TrainingExample("점자 기기 좀 연결해줘", "ConnectDevice", {}),
    TrainingExample("3번 점 좀 올려줘", "RaiseDots", {"DotNumber": "3"}),
    TrainingExample("1번하고 3번 점 켜줘", "RaiseDots", {"DotNumber": "1,3"}),
    TrainingExample("여섯 번째 점 올려", "RaiseDots", {"DotNumber": "6"}),
    TrainingExample("5번 점 내려줘", "LowerDots", {"DotNumber": "5"}),
    TrainingExample("점 전부 다 내려줘", "LowerDots", {"DotNumber": "all"}),
    TrainingExample("설정 화면 좀 띄워줘", "OpenScreen", {"ScreenName": "settings"}),
    TrainingExample("점자 사전 열어줘", "OpenScreen", {"ScreenName": "dictionary"}),
    TrainingExample("내 목소리 설정 열어줘", "OpenScreen", {"ScreenName": "voice"}),
    TrainingExample("점자 배우러 갈래", "OpenScreen", {"ScreenName": "practice"}),
    TrainingExample("이전 화면 돌아가", "GoBack", {}),
    TrainingExample("네가 할 수 있는 게 뭐야", "ShowHelp", {}),
    TrainingExample("아니야 됐어", "Cancel", {}),
]

# 캡슐이 처리할 수 없는 발화. 낮은 확신도로 걸러 내야 한다.
OUT_OF_DOMAIN: list[str] = [
    "내일 서울 날씨 알려줘",
    "노래 틀어줘",
    "엄마한테 전화해줘",
    "오늘 뉴스 읽어줘",
    "알람 일곱 시에 맞춰줘",
    "근처 맛집 찾아줘",
]


# --- 조사/말투 ----------------------------------------------------------------


def particle_ro(word: str) -> str:
    """'~로 / ~으로' 를 받침에 맞춰 고른다."""
    if not word:
        return "로"
    parts = decompose_syllable(word[-1])
    if parts is None:
        return "로"
    jong = parts[2]
    # 받침이 없거나 ㄹ(인덱스 8)이면 '로'
    return "로" if jong in (0, 8) else "으로"


def _polite_variants(text: str) -> list[str]:
    variants = [text]
    if text and text[-1].isalnum() and not text.endswith("요"):
        variants.append(text + "요")
    variants.append("좀 " + text)
    return variants


# --- 오인식 흉내 --------------------------------------------------------------

_CONFUSABLE_JONG = {19: 20, 20: 19, 4: 0, 21: 0, 8: 0}  # ㅅ<->ㅆ, ㄴ/ㅇ/ㄹ 탈락
_CONFUSABLE_JUNG = {1: 5, 5: 1, 3: 7, 7: 3}  # ㅐ<->ㅔ, ㅒ<->ㅖ


def noisy(text: str, rng: random.Random) -> str:
    """음성 인식이 실제로 내는 오류를 흉내 낸다."""
    mode = rng.random()
    if mode < 0.3:
        return text.replace(" ", "")

    chars = list(text)
    positions = [i for i, ch in enumerate(chars) if decompose_syllable(ch) is not None]
    if not positions:
        return text.replace(" ", "")

    index = rng.choice(positions)
    cho, jung, jong = decompose_syllable(chars[index])  # type: ignore[misc]

    if mode < 0.7 and jong in _CONFUSABLE_JONG:
        jong = _CONFUSABLE_JONG[jong]
    elif jung in _CONFUSABLE_JUNG:
        jung = _CONFUSABLE_JUNG[jung]
    elif jong:
        jong = 0
    else:
        return text.replace(" ", "")

    chars[index] = compose_syllable(cho, jung, jong)
    return "".join(chars)


# --- 생성 ---------------------------------------------------------------------

def _slot_choices(slot: str) -> list[tuple[str, dict[str, str]]]:
    """슬롯 하나에 넣을 수 있는 (표현, concept) 목록."""
    if slot == "screen":
        return [
            (surface, {"ScreenName": value})
            for value, surfaces in SCREEN_VALUES.items()
            for surface in surfaces[:4]
        ]
    if slot == "level":
        return [
            (surface, {"BrailleLevel": value})
            for value, surfaces in LEVEL_VALUES.items()
            for surface in surfaces[:3]
        ]
    if slot == "period":
        return [
            (surface, {"Period": value})
            for value, surfaces in PERIOD_VALUES.items()
            for surface in surfaces[:3]
        ]
    if slot == "count":
        return [(surface, {"QuestionCount": value}) for surface, value in COUNT_CHOICES]
    if slot == "dot":
        return [(surface, {"DotNumber": value}) for surface, value in DOT_CHOICES]
    if slot == "all":
        return [(surface, {"DotNumber": value}) for surface, value in ALL_DOTS_CHOICES]
    raise ValueError(f"모르는 슬롯: {slot}")


def _slots_in(template: str) -> list[str]:
    return [slot for slot in ("screen", "level", "period", "count", "dot", "all") if "{" + slot + "}" in template]


def _fill(template: str, rng: random.Random, max_combos: int) -> list[tuple[str, dict[str, str]]]:
    slots = _slots_in(template)
    if not slots:
        return [(template.replace("{ro}", ""), {})]

    options = [_slot_choices(slot) for slot in slots]
    combos = list(itertools.product(*options))
    if len(combos) > max_combos:
        combos = rng.sample(combos, max_combos)

    filled: list[tuple[str, dict[str, str]]] = []
    for combo in combos:
        text = template
        concepts: dict[str, str] = {}
        for slot, (surface, concept) in zip(slots, combo):
            text = text.replace("{" + slot + "}", surface)
            concepts.update(concept)
            if slot == "screen":
                text = text.replace("{ro}", particle_ro(surface))
        filled.append((text.replace("{ro}", ""), concepts))
    return filled


def generate(
    seed: int = 20260901,
    max_combos: int = 24,
    noise_ratio: float = 0.4,
    max_per_label: int = 300,
) -> list[TrainingExample]:
    """액션별 학습 발화를 만든다.

    템플릿 수가 액션마다 달라 그대로 두면 클래스가 크게 기울기 때문에
    라벨당 [max_per_label]건으로 잘라 균형을 맞춘다.
    """
    rng = random.Random(seed)
    seen: set[tuple[str, str]] = set()
    examples: list[TrainingExample] = []

    def add(text: str, action: str, concepts: dict[str, str], noise_repeats: int = 1) -> None:
        for variant in _polite_variants(text):
            candidates = [variant]
            for _ in range(noise_repeats):
                if rng.random() < noise_ratio:
                    candidates.append(noisy(variant, rng))
            for candidate in candidates:
                key = (candidate, action)
                if key in seen:
                    continue
                seen.add(key)
                examples.append(TrainingExample(candidate, action, dict(concepts)))

    for action, templates in TEMPLATES.items():
        for template in templates:
            for text, concepts in _fill(template, rng, max_combos):
                add(text, action, concepts)

    # 부정 예시는 템플릿 수가 적으므로 오인식 변형을 더 많이 만든다.
    for template in OOD_TEMPLATES:
        add(template, OOD_LABEL, {}, noise_repeats=4)

    rng.shuffle(examples)
    return _cap_per_label(examples, max_per_label)


def _cap_per_label(examples: list[TrainingExample], cap: int) -> list[TrainingExample]:
    kept: list[TrainingExample] = []
    counts: dict[str, int] = {}
    for example in examples:
        count = counts.get(example.action, 0)
        if count >= cap:
            continue
        counts[example.action] = count + 1
        kept.append(example)
    return kept
