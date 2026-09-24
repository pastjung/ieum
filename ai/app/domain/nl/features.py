"""발화 피처 추출 — 안드로이드 앱과 **똑같은 정의**를 써야 한다.

짝이 되는 코틀린 코드
- 정규화/자모: `app/src/main/java/com/itda/iva/voice/text/Hangul.kt`
- 피처: `app/src/main/java/com/itda/iva/nlu/NlModel.kt` 의 `NlFeatures`

음성 인식 결과는 띄어쓰기와 받침이 흔들리므로
(1) 공백·문장부호를 없애고 (2) 문자 2·3-gram과 (3) 자모 3-gram을 함께 쓴다.
자모 n-gram이 있어야 "복습"과 "복십"처럼 한 획 차이 나는 오인식을 견딘다.
"""

from __future__ import annotations

FEATURE_SPEC = "char2,char3,jamo3"

SYLLABLE_BASE = 0xAC00
SYLLABLE_LAST = 0xD7A3

CHOSEONG = [
    "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
    "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
]

JUNGSEONG = [
    "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ",
    "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ",
]

JONGSEONG = [
    "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ",
    "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ",
    "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
]


def normalize(text: str) -> str:
    """소문자로 바꾸고 공백·문장부호를 없앤 비교 전용 문자열."""
    return "".join(ch for ch in text.lower() if ch.isalnum())


def decompose_syllable(char: str) -> tuple[int, int, int] | None:
    """한글 음절이면 (초성, 중성, 종성) 인덱스, 아니면 None."""
    code = ord(char)
    if not (SYLLABLE_BASE <= code <= SYLLABLE_LAST):
        return None
    index = code - SYLLABLE_BASE
    return index // (21 * 28), index % (21 * 28) // 28, index % 28


def compose_syllable(cho: int, jung: int, jong: int) -> str:
    """(초성, 중성, 종성) 인덱스를 다시 음절 하나로."""
    return chr(SYLLABLE_BASE + (cho * 21 + jung) * 28 + jong)


def to_jamo(text: str) -> str:
    """한글을 자모로 풀어 쓴다. 한글이 아닌 글자는 그대로 둔다."""
    out: list[str] = []
    for char in text:
        parts = decompose_syllable(char)
        if parts is None:
            out.append(char)
            continue
        cho, jung, jong = parts
        out.append(CHOSEONG[cho])
        out.append(JUNGSEONG[jung])
        if jong:
            out.append(JONGSEONG[jong])
    return "".join(out)


def extract(text: str) -> list[str]:
    """모델이 쓰는 피처 목록(중복 포함 — 다항 나이브 베이즈라 등장 횟수를 센다)."""
    normalized = normalize(text)
    if not normalized:
        return []

    features: list[str] = []
    for n in (2, 3):
        for i in range(len(normalized) - n + 1):
            features.append(f"c{n}:{normalized[i:i + n]}")

    jamo = to_jamo(normalized)
    for i in range(len(jamo) - 2):
        features.append(f"j3:{jamo[i:i + 3]}")
    return features


def edit_distance(a: str, b: str) -> int:
    """레벤슈타인 거리. 코틀린 `Hangul.editDistance`와 같은 계산."""
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)

    previous = list(range(len(b) + 1))
    for i, char_a in enumerate(a, start=1):
        current = [i] + [0] * len(b)
        for j, char_b in enumerate(b, start=1):
            current[j] = min(
                previous[j - 1] + (0 if char_a == char_b else 1),
                previous[j] + 1,
                current[j - 1] + 1,
            )
        previous = current
    return previous[len(b)]


def jamo_distance(a: str, b: str) -> int:
    """자모 단위 편집 거리. "복습"과 "복스"는 1이 나온다."""
    return edit_distance(to_jamo(a), to_jamo(b))


def fuzzy_contains(haystack: str, needle: str, max_distance: int) -> bool:
    """[haystack] 안에 [needle]과 자모 거리 [max_distance] 이하로 닮은 구간이 있는지.

    코틀린 `Hangul.fuzzyIndexOfEnd`와 같은 방식(길이를 앞뒤로 열어 둔 슬라이딩 윈도우).
    """
    if not needle or not haystack:
        return False
    if max_distance == 0:
        return needle in haystack

    min_length = max(1, len(needle) - max_distance)
    max_length = len(needle) + max_distance
    for start in range(len(haystack)):
        for length in range(min_length, max_length + 1):
            end = start + length
            if end > len(haystack):
                break
            if jamo_distance(haystack[start:end], needle) <= max_distance:
                return True
    return False
