"""curriculum.py — 6단계 커리큘럼 (자음 · 모음 · 약자 · 약어 · 단어 · 문장)

결정 사항: 자가 진단 없음, 사용자가 단계를 직접 고름, 자동 승급 없음(다음 단계 '제안'만).
여기서는 (1) 개념(KC) → 단계, (2) 문항 → 단계, (3) 단계별 핵심 개념(첫 세션 진단용) 을 정한다.
교육자 확인 뒤 바뀔 수 있는 초안이다. 받침·된소리를 자음에 둔 것, 첫소리 ㅇ 생략을 모음에 둔 것은 제안.
"""

STAGES = ["자음", "모음", "약자", "약어", "단어", "문장"]
STAGE_TO_LEVEL = {
    "자음": "CONSONANT",
    "모음": "VOWEL",
    "약자": "CONTRACTION",
    "약어": "CONTRACTION_WORD",
    "단어": "WORD",
    "문장": "SENTENCE",
}  # 백엔드 Level enum (problems.level = 단계)
LEVEL_TO_STAGE = {v: k for k, v in STAGE_TO_LEVEL.items()}
STAGE_INDEX = {s: i for i, s in enumerate(STAGES)}
BASIC_VOWELS = set("ㅏㅑㅓㅕㅗㅛㅜㅠㅡㅣ")


def stage_of_kc(kc: str) -> str:
    cat = kc.partition(":")[0]
    if cat in ("초성", "종성") or kc == "규칙:된소리표":
        return "자음"
    if cat == "중성" or kc in ("규칙:초성ㅇ생략", "규칙:구분표(예)", "규칙:구분표(애)"):
        return "모음"
    if cat == "약자" or kc == "규칙:약자미사용":
        return "약자"
    if cat == "약어":
        return "약어"
    return "단어"


def stage_of_item(kc_all: list[str], n_syl: int, source: str | None = None, word: str = "") -> str:
    """문항의 단계 = 포함 개념 중 가장 높은 단계. 단, 개념이 자음·모음뿐이어도 3음절 이상이면 '단어'."""
    if source == "drill":
        non_vowel = [k for k in kc_all if not k.startswith("중성")]
        return stage_of_kc(non_vowel[0]) if non_vowel else "모음"
    top = max((STAGE_INDEX[stage_of_kc(k)] for k in kc_all), default=0)
    if top <= STAGE_INDEX["모음"]:
        # 자음/모음 단계 낱말: 기본 모음만 쓰면 자음 단계에도 낼 수 있음
        if n_syl >= 3:
            return "단어"
        only_basic = all(
            not k.startswith("중성") or k.split(":")[1] in BASIC_VOWELS for k in kc_all
        )
        rule_free = not any(k.startswith("규칙:") and k != "규칙:된소리표" for k in kc_all)
        return "자음" if (only_basic and rule_free) else "모음"
    if n_syl >= 3 and top <= STAGE_INDEX["약자"]:
        return "단어"
    return STAGES[top]


# 첫 세션(진단 모드)에서 우선 확인하는 핵심 개념. 이 개념들의 드릴을 먼저 낸다.
CORE_KCS = {
    "자음": [
        "초성:ㄱ",
        "초성:ㄴ",
        "초성:ㄷ",
        "초성:ㄹ",
        "초성:ㅁ",
        "초성:ㅂ",
        "초성:ㅅ",
        "초성:ㅈ",
        "종성:ㄱ",
        "종성:ㄴ",
        "종성:ㄹ",
        "종성:ㅁ",
        "종성:ㅇ",
        "규칙:된소리표",
    ],
    "모음": [
        "중성:ㅏ",
        "중성:ㅓ",
        "중성:ㅗ",
        "중성:ㅜ",
        "중성:ㅡ",
        "중성:ㅣ",
        "중성:ㅑ",
        "중성:ㅕ",
        "중성:ㅐ",
        "중성:ㅔ",
        "중성:ㅘ",
        "규칙:초성ㅇ생략",
    ],
    "약자": [
        "약자:ㄱㅏ",
        "약자:ㄴㅏ",
        "약자:ㄷㅏ",
        "약자:ㅅㅏ",
        "약자:ㅎㅏ",
        "약자:억",
        "약자:언",
        "약자:은",
        "약자:을",
        "약자:인",
        "약자:것",
        "규칙:약자미사용",
    ],
    "약어": [
        "약어:그래서",
        "약어:그러나",
        "약어:그러면",
        "약어:그러므로",
        "약어:그런데",
        "약어:그리고",
        "약어:그리하여",
    ],
    "단어": [],
    "문장": [],
}


def lower_stages(stage: str) -> list[str]:
    return STAGES[: STAGE_INDEX[stage]]
