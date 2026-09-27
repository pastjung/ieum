"""drill_items.py — 낱자·드릴 문항을 채점기 item 으로 만든다.

자음·모음 단계 문항은 어절이 아니라 낱자('ㄱ', 'ㅙ')이거나 우리 시드의 드릴 이름('초성 ㄱ', '받침 ㄱ', '모음 ㅏ',
'약자 가', '약어 그래서', '음절 아')이다. 점역기(kbraille.analyze)는 완성형 음절만 받으므로 이런 텍스트는
여기서 직접 셀·요소를 만들어 grader 에 item 으로 넘긴다.

낱자 'ㄱ' 은 첫소리(4점)인지 받침(1점)인지 텍스트만으로는 알 수 없다. 백엔드가 함께 보내는 expectedPatterns
(등록 시 저장된 정답)와 맞춰 보고 정한다. 없으면 첫소리로 본다.
"""

import re
from typing import Any

from app.infrastructure.braille_core import kbraille as kb
from app.infrastructure.braille_core.braille_utils import dots_to_int, str_to_dots

COMPAT_JAMO_FIRST, COMPAT_JAMO_LAST = 0x3131, 0x3163
_PREFIX = re.compile(r"^(초성|첫소리|받침|종성|모음|중성|약자|약어|음절)\s*(\S+)$")
_ONSET_ONLY = set(kb.CHO_DOTS) | set(kb.DOUBLE_CHO)
_CODA_ONLY = set(kb.DOUBLE_JONG)


def is_drill_text(text: str) -> bool:
    t = text.strip()
    if _PREFIX.match(t):
        return True
    return len(t) == 1 and COMPAT_JAMO_FIRST <= ord(t) <= COMPAT_JAMO_LAST


def _item(
    cells: list[list[int]], kcs: list[str], rules: list[str | None], word: str
) -> dict[str, Any]:
    return {
        "source": "drill",
        "word": word,
        "cells": cells,
        "kc": kcs,
        "rules_per_cell": rules,
        "syllables": [],
        "kc_rules": [],
    }


def _onset(jamo: str) -> dict[str, Any] | None:
    if jamo in kb.CHO_DOTS:
        return _item([str_to_dots(kb.CHO_DOTS[jamo])], [f"초성:{jamo}"], ["제1항"], f"초성 {jamo}")
    if jamo in kb.DOUBLE_CHO:  # 된소리: 된소리표(6) + 첫소리
        base = kb.DOUBLE_CHO[jamo]
        return _item(
            [[6], str_to_dots(kb.CHO_DOTS[base])],
            ["규칙:된소리표", f"초성:{base}"],
            ["제2항", "제1항"],
            f"초성 {jamo}",
        )
    return None


def _coda(jamo: str) -> dict[str, Any] | None:
    if jamo == "ㅆ":  # 제4항: 받침 ㅆ 은 3·4점 한 칸 (kbraille._jong 의 특수 처리와 동일)
        return _item([[3, 4]], ["종성:ㅆ"], ["제4항"], "받침 ㅆ")
    if jamo == "ㄲ":  # 제4항: 받침 ㄲ 은 받침 ㄱ(1점)을 두 번
        return _item([[1], [1]], ["종성:ㄲ", "종성:ㄲ"], ["제4항", "제4항"], "받침 ㄲ")
    if jamo in kb.JONG_DOTS:
        return _item([str_to_dots(kb.JONG_DOTS[jamo])], [f"종성:{jamo}"], ["제3항"], f"받침 {jamo}")
    if jamo in kb.DOUBLE_JONG:  # 겹받침: 두 받침을 이어 씀
        parts = kb.DOUBLE_JONG[jamo]
        return _item(
            [str_to_dots(kb.JONG_DOTS[p]) for p in parts],
            [f"종성:{jamo}"] * len(parts),
            ["제5항"] * len(parts),
            f"받침 {jamo}",
        )
    return None


def _vowel(jamo: str) -> dict[str, Any] | None:
    dots = kb.JUNG_DOTS.get(jamo)
    if not dots:
        return None
    rule = "제6항" if jamo in "ㅏㅑㅓㅕㅗㅛㅜㅠㅡㅣ" else "제7항"
    return _item(
        [str_to_dots(d) for d in dots],
        [f"중성:{jamo}"] * len(dots),
        [rule] * len(dots),
        f"모음 {jamo}",
    )


def _compose(cho: str, jung: str) -> str:
    return chr(0xAC00 + kb.CHO.index(cho) * 588 + kb.JUNG.index(jung) * 28)


def _abbr(text: str) -> dict[str, Any] | None:
    # 시드의 약자 드릴 이름은 'ㄱㅏ' 처럼 자모 쌍이다 → 음절 '가' 로 합쳐서 처리
    if len(text) == 2 and text[0] in kb.CHO and text[1] in kb.JUNG:
        text = _compose(text[0], text[1])
    if text in kb.ABBR_WORDS:  # 약어 (두 칸)
        an = kb.analyze(text)
        return _item(
            [c.dots for c in an.cells],
            [c.kc for c in an.cells],
            [c.rule for c in an.cells],
            f"약어 {text}",
        )
    if len(text) == 1:  # 약자 음절 ('가', '억', '것' …): 한 음절 변환값이 약자 셀이면 그대로
        try:
            an = kb.analyze(text)
        except ValueError:
            return None
        if an.cells and all(c.kc.startswith("약자") for c in an.cells):
            return _item(
                [c.dots for c in an.cells],
                [c.kc for c in an.cells],
                [c.rule for c in an.cells],
                f"약자 {text}",
            )
    return None


def build_drill_item(
    text: str, expected_patterns: list[int] | None = None
) -> dict[str, Any] | None:
    """드릴 텍스트 → grader item. 드릴이 아니면 None (어절은 kbraille.analyze 로)."""
    t = text.strip()
    m = _PREFIX.match(t)
    if m:
        kind, jamo = m.group(1), m.group(2)
        if kind in ("초성", "첫소리"):
            return _onset(jamo)
        if kind in ("받침", "종성"):
            return _coda(jamo)
        if kind in ("모음", "중성"):
            return _vowel(jamo)
        if kind in ("약자", "약어"):
            return _abbr(jamo)
        if kind == "음절":  # '음절 아' 처럼 첫소리 ㅇ 생략 규칙 드릴: 한 음절 변환값 그대로
            try:
                an = kb.analyze(jamo)
            except ValueError:
                return None
            item = _item(
                [c.dots for c in an.cells], [c.kc for c in an.cells], [c.rule for c in an.cells], t
            )
            item["syllables"] = [
                {
                    "idx": s.idx,
                    "text": s.text,
                    "cho": s.cho,
                    "jung": s.jung,
                    "jong": s.jong,
                    "cells": s.cells,
                    "rule_kcs": s.rule_kcs,
                }
                for s in an.syllables
            ]
            item["kc_rules"] = [{"syl": r.syl, "kc": r.kc, "rule": r.rule} for r in an.kc_rules]
            return item
        return None
    if not (len(t) == 1 and COMPAT_JAMO_FIRST <= ord(t) <= COMPAT_JAMO_LAST):
        return None
    if t in kb.JUNG_DOTS:
        return _vowel(t)
    onset, coda = _onset(t), _coda(t)
    if onset and coda and expected_patterns:  # 첫소리·받침 둘 다 가능한 자음 → 저장된 정답으로 결정
        if [dots_to_int(c) for c in coda["cells"]] == expected_patterns:
            return coda
        return onset
    return onset or coda
