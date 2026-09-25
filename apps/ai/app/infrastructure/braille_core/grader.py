"""grader.py — 채점기

입력: 정답 텍스트(또는 은행 문항) + 학습자가 입력한 셀열(0~63 정수 또는 점 리스트)
출력: 정답 여부, 셀별 결과, 오류 목록(유형·위치·KC·id), KC별 결과(셀 KC + 셀 없는 규칙 KC), TTS 피드백 문장

  from grader import grade
  r = grade("음식", [54, 42, 34, 32, 21, 1])
  r["correct"] -> False
  r["feedback"]["tts"] -> "첫소리 이응은 점자로 적지 않아요. '음'은 두 칸이에요."

오류 판정은 error_model.diagnose 가 하고, 이 모듈은 (1) 사람이 들을 문장 (2) 백엔드가 저장할 레코드 형태만 담당한다.
"""

from typing import Any

from app.infrastructure.braille_core import error_model as em
from app.infrastructure.braille_core import kbraille as kb
from app.infrastructure.braille_core.braille_utils import cells_to_unicode, dots_to_int, int_to_dots

GRADER_VERSION = "0.1.0"

# ── TTS 용 이름 ─────────────────────────────────────────────────────────────
JAMO_NAME = {
    "ㄱ": "기역",
    "ㄴ": "니은",
    "ㄷ": "디귿",
    "ㄹ": "리을",
    "ㅁ": "미음",
    "ㅂ": "비읍",
    "ㅅ": "시옷",
    "ㅇ": "이응",
    "ㅈ": "지읒",
    "ㅊ": "치읓",
    "ㅋ": "키읔",
    "ㅌ": "티읕",
    "ㅍ": "피읖",
    "ㅎ": "히읗",
    "ㄲ": "쌍기역",
    "ㄸ": "쌍디귿",
    "ㅃ": "쌍비읍",
    "ㅆ": "쌍시옷",
    "ㅉ": "쌍지읒",
    "ㄳ": "기역시옷",
    "ㄵ": "니은지읒",
    "ㄶ": "니은히읗",
    "ㄺ": "리을기역",
    "ㄻ": "리을미음",
    "ㄼ": "리을비읍",
    "ㄽ": "리을시옷",
    "ㄾ": "리을티읕",
    "ㄿ": "리을피읖",
    "ㅀ": "리을히읗",
    "ㅄ": "비읍시옷",
    "ㅏ": "아",
    "ㅑ": "야",
    "ㅓ": "어",
    "ㅕ": "여",
    "ㅗ": "오",
    "ㅛ": "요",
    "ㅜ": "우",
    "ㅠ": "유",
    "ㅡ": "으",
    "ㅣ": "이",
    "ㅐ": "애",
    "ㅒ": "얘",
    "ㅔ": "에",
    "ㅖ": "예",
    "ㅘ": "와",
    "ㅙ": "왜",
    "ㅚ": "외",
    "ㅝ": "워",
    "ㅞ": "웨",
    "ㅟ": "위",
    "ㅢ": "의",
}
ORDINAL = ["첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한", "열두"]


def speak_kc(kc: str) -> str:
    """KC 태그 → 들을 수 있는 이름. '초성:ㄱ' → '첫소리 기역', '약자:ㅅㅏ' → '약자 사'"""
    cat, _, val = kc.partition(":")
    if cat == "초성":
        return f"첫소리 {JAMO_NAME.get(val, val)}"
    if cat == "종성":
        return f"받침 {JAMO_NAME.get(val, val)}"
    if cat == "중성":
        return f"모음 {JAMO_NAME.get(val, val)}"
    if cat == "약자":
        if val.endswith("ㅏ") and len(val) == 2:
            syl = _compose(val[0], "ㅏ")
            return f"약자 {syl}"
        return f"약자 {val.split('(')[0]}"
    if cat == "약어":
        return f"약어 {val}"
    if cat == "규칙":
        return {
            "된소리표": "된소리표",
            "구분표(예)": "구분표",
            "구분표(애)": "구분표",
            "초성ㅇ생략": "첫소리 이응 생략",
            "약자미사용": "모음 앞 약자 미사용",
        }.get(val, val)
    return kc


def _compose(cho: str, jung: str, jong: str = "") -> str:
    return chr(0xAC00 + kb.CHO.index(cho) * 588 + kb.JUNG.index(jung) * 28 + kb.JONG.index(jong))


def josa(word: str, pair: tuple[str, str]) -> str:
    """받침 유무로 조사 선택. pair 예: ("이","가"), ("을","를"), ("으로","로"), ("과","와"), ("은","는")"""
    if not word:
        return pair[1]
    ch = word[-1]
    code = ord(ch) - 0xAC00
    if not 0 <= code < 11172:
        return pair[1]
    jong = kb.JONG[code % 28]
    if pair[0] == "으로" and jong == "ㄹ":
        return "로"
    return pair[0] if jong else pair[1]


def speak_dots(dots: list[int]) -> str:
    return ", ".join(str(d) for d in dots) + "점"


def ordinal(i: int) -> str:
    return f"{ORDINAL[i]} 번째" if i < len(ORDINAL) else f"{i + 1} 번째"


# ── 피드백 문장 ─────────────────────────────────────────────────────────────
SEVERITY = {
    "silent_onset_written": 3,
    "abbr_expanded": 3,
    "abbr_overused": 3,
    "marker_missing": 3,
    "cell_omitted": 2,
    "cell_inserted": 2,
    "transposition": 2,
    "jamo_confusion": 2,
    "mirror": 1,
    "vflip": 1,
    "rot180": 1,
    "shift_down": 1,
    "shift_up": 1,
    "dot_missing": 0,
    "dot_extra": 0,
    "dot_mixed": 0,
}


def _sentence(
    e: dict[str, Any],
    cells: list[list[int]],
    kcs: list[str],
    syllables: list[dict[str, Any]],
    word: str,
) -> str:
    t: str = e["type"]
    i: int = e["cell"] if e.get("cell") is not None else -1
    kc: str = e.get("kc") or (kcs[i] if 0 <= i < len(kcs) else "")
    name = speak_kc(kc) if kc else "이 칸"
    exp = speak_dots(cells[i]) if 0 <= i < len(cells) else ""
    pos = ordinal(i) if i >= 0 else ""
    d = e.get("detail", {})
    reads = [speak_kc(r) for r in d.get("reads_as", []) if not r.startswith("규칙")]
    reads_s = f" 지금 쓴 건 '{reads[0]}'{josa(reads[0], ('으로', '로'))} 읽혀요." if reads else ""
    syl = syllables[d["syl"]]["text"] if "syl" in d and syllables else ""
    if t == "dot_missing":
        return f"{pos} 칸에서 {speak_dots(d.get('missing', []))}이 빠졌어요. {name}{josa(name, ('은', '는'))} {exp}이에요."
    if t == "dot_extra":
        return f"{pos} 칸에 {speak_dots(d.get('extra', []))}이 더 들어갔어요. {name}{josa(name, ('은', '는'))} {exp}이에요."
    if t == "dot_mixed":
        return f"{pos} 칸이 달라요. {name}{josa(name, ('은', '는'))} {exp}이에요."
    if t == "mirror":
        return (
            f"{pos} 칸이 좌우로 뒤집혔어요.{reads_s} {name}{josa(name, ('은', '는'))} {exp}이에요."
        )
    if t == "vflip":
        return f"{pos} 칸이 위아래로 뒤집혔어요.{reads_s} {name}{josa(name, ('은', '는'))} {exp}이에요."
    if t == "rot180":
        return (
            f"{pos} 칸이 반 바퀴 돌아갔어요.{reads_s} {name}{josa(name, ('은', '는'))} {exp}이에요."
        )
    if t == "shift_down":
        return f"{pos} 칸이 한 줄 아래로 내려갔어요.{reads_s} {name}{josa(name, ('은', '는'))} {exp}이에요."
    if t == "shift_up":
        return f"{pos} 칸이 한 줄 위로 올라갔어요.{reads_s} {name}{josa(name, ('은', '는'))} {exp}이에요."
    if t == "jamo_confusion":
        r0 = reads[0] if reads else "다른 글자"
        return f"{pos} 칸에 {r0}{josa(r0, ('을', '를'))} 썼어요. 여기는 {name}, {exp}이에요."
    if t == "cell_omitted":
        return f"{pos} 칸, {name}{josa(name, ('이', '가'))} 빠졌어요. {exp}이에요."
    if t == "cell_inserted":
        return f"필요 없는 칸이 하나 들어갔어요.{reads_s}"
    if t == "transposition":
        return f"{pos} 칸과 {ordinal(i + 1)} 칸의 순서가 바뀌었어요."
    if t == "abbr_expanded":
        if kc.startswith("약어:"):
            return f"'{kc.split(':')[1]}'{josa(kc.split(':')[1], ('은', '는'))} 약어 두 칸으로 써요. {speak_dots(cells[i])}, {speak_dots(cells[i + 1]) if i + 1 < len(cells) else ''}이에요."
        if kc == "약자:것":
            return f"'것'은 약자 두 칸으로 써요. {speak_dots(cells[i])}, {speak_dots(cells[i + 1]) if i + 1 < len(cells) else ''}이에요."
        return f"{name}{josa(name, ('은', '는'))} 약자 한 칸으로 써요. {exp}이에요."
    if t == "abbr_overused":
        # 나·다·마… 뒤에 모음이 오면 약자를 쓰지 않음. i = ㅏ 셀
        cho_kc = kcs[i - 1] if i and i - 1 < len(kcs) else ""
        cho_name = speak_kc(cho_kc)
        return f"'{syl}' 뒤에 모음이 이어지면 약자를 쓰지 않아요. {cho_name}{josa(cho_name, ('과', '와'))} 모음 아를 따로 써요."
    if t == "marker_missing":
        return f"{name}, {exp}이 빠졌어요."
    if t == "silent_onset_written":
        n = len(syllables[d["syl"]]["cells"]) if syllables else 1
        return f"첫소리 이응은 점자로 적지 않아요. '{syl}'은 {ORDINAL[n - 1] if n <= len(ORDINAL) else n} 칸이에요."
    return f"{pos} 칸이 달라요."


def _info_when_correct(kcs: list[str], kc_rules: list[dict[str, Any]]) -> str:
    """맞았을 때도 관련 정보를 준다(멘토 피드백). 약자·약어·규칙이 쓰였으면 알려줌."""
    used: list[str] = []
    for kc in dict.fromkeys(kcs):
        if kc.startswith(("약자:", "약어:")):
            used.append(speak_kc(kc))
    for r in kc_rules:
        if r["kc"] == "규칙:초성ㅇ생략":
            used.append("첫소리 이응 생략")
        elif r["kc"] == "규칙:약자미사용":
            used.append("모음 앞 약자 미사용")
    if not used:
        return ""
    items = list(dict.fromkeys(used))
    return "이 단어에는 " + ", ".join(items) + josa(items[-1], ("이", "가")) + " 들어 있어요."


# ── 채점 ──────────────────────────────────────────────────────────────────
def _to_dots(x: int | list[int]) -> list[int]:
    return int_to_dots(x) if isinstance(x, int) else list(x)


def grade(
    text: str,
    actual: list[int] | list[list[int]],
    response_ms: int | None = None,
    hint_used: bool = False,
    item: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """item(은행 레코드)이 주어지면 그 정답을 쓰고, 아니면 text 를 점역기로 변환한다."""
    if item is not None and item.get("source") == "drill":
        cells, kcs, rules = item["cells"], item["kc"], item["rules_per_cell"]
        syllables, kc_rules = item.get("syllables", []), item.get("kc_rules", [])
    else:
        an = kb.analyze(text)
        cells = [c.dots for c in an.cells]
        kcs = [c.kc for c in an.cells]
        rules: list[str | None] = [c.rule for c in an.cells]
        syllables = [
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
        kc_rules: list[dict[str, Any]] = [
            {"syl": r.syl, "kc": r.kc, "rule": r.rule} for r in an.kc_rules
        ]
    actual_dots: list[list[int]] = [_to_dots(x) for x in actual]
    correct = em.is_correct(cells, actual_dots)
    errors = [] if correct else em.diagnose(cells, kcs, actual_dots, rules, syllables)

    # 셀별 결과: 정렬에서 match 인 셀만 True, 오류가 가리키는 셀은 False (초성 ㅇ 표기는 셀 자체는 맞음)
    cell_ok = [False] * len(cells)
    for op, i, _j in em.align(cells, actual_dots):
        if op == "match" and i is not None:
            cell_ok[i] = True
    for e in errors:
        if e["cell"] is not None and e["type"] != "silent_onset_written" and e["cell"] < len(cells):
            cell_ok[e["cell"]] = False

    # KC 별 결과 (셀 KC + 규칙 KC) — 백엔드 learner_kc_state 갱신, HLR/KT 학습의 원자료
    kc_outcomes: list[dict[str, Any]] = [
        {"kc": k, "cell": i, "correct": cell_ok[i]} for i, k in enumerate(kcs)
    ]
    rule_failed = {
        (e["kc"], e.get("detail", {}).get("syl"))
        for e in errors
        if e["kc"] and e["kc"].startswith("규칙:")
    }
    for r in kc_rules:
        failed = (r["kc"], r["syl"]) in rule_failed or (r["kc"], None) in rule_failed
        kc_outcomes.append({"kc": r["kc"], "syl": r["syl"], "correct": not failed})

    # 피드백
    errors_sorted = sorted(errors, key=lambda e: -SEVERITY.get(e["type"], 0))
    lines = [_sentence(e, cells, kcs, syllables, text) for e in errors_sorted]
    if correct:
        tts = "정답이에요."
        info = _info_when_correct(kcs, kc_rules)
        if info:
            tts += " " + info
        severity = "none"
    else:
        tts = " ".join(lines[:2])
        if len(lines) > 2:
            tts += f" 그 외 {len(lines) - 2}군데가 더 달라요."
        top = errors_sorted[0]["type"]
        severity = {3: "rule", 2: "cell", 1: "shape", 0: "dot"}[SEVERITY.get(top, 0)]

    return {
        "text": text,
        "correct": correct,
        "expected_cells": [dots_to_int(c) for c in cells],
        "actual_cells": [dots_to_int(c) for c in actual_dots],
        "expected": cells_to_unicode(cells),
        "actual": cells_to_unicode(actual_dots),
        "cell_results": cell_ok,
        "kc": kcs,
        "kc_rules": [r["kc"] for r in kc_rules],
        "errors": errors,
        "kc_outcomes": kc_outcomes,
        "feedback": {"tts": tts, "lines": lines, "severity": severity},
        "response_ms": response_ms,
        "hint_used": hint_used,
        "grader_version": GRADER_VERSION,
    }
