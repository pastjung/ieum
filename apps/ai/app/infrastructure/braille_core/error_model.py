"""error_model.py — 점자 오류 모델 (채점기와 시뮬레이터가 공유하는 핵심)

  채점기  : diagnose(expected_cells, expected_kcs, actual_cells)  → 오류 목록   (판정)
  시뮬레이터: candidate_errors(cells, kcs, rules)                  → 그럴듯한 오답 후보 (생성)

두 기능이 같은 오류 유형 정의(TAXONOMY)와 같은 점형 기하 변환(TRANSFORMS)을 쓰기 때문에
"시뮬레이터가 만든 오류를 채점기가 같은 이름으로 판정한다"가 보장된다.

셀 표현: 점 번호 리스트 [1..6] (braille_utils 규약). 함수 내부에서는 frozenset 으로 다룬다.
"""

from collections import defaultdict
from typing import Any

from app.infrastructure.braille_core import kbraille as kb
from app.infrastructure.braille_core.braille_utils import dots_to_int

Dots = list[int]
Cells = list[list[int]]
Error = dict[str, Any]

# ── 1. 오류 유형 정의 ──────────────────────────────────────────────────────
TAXONOMY = {
    # 셀 내부(점 단위)
    "dot_missing": "점 누락 — 정답 점 중 일부를 안 찍음",
    "dot_extra": "점 추가 — 정답에 없는 점을 찍음",
    "dot_mixed": "점 혼합 — 누락과 추가가 섞여 다른 유형으로 설명되지 않음",
    # 셀 내부(기하 변환) — 정답 점형에 변환을 적용하면 입력과 같아짐
    "mirror": "좌우 대칭 — 1↔4, 2↔5, 3↔6 (초성↔종성 ㄱㄹㅂㅅㅈㅊ, ㅏ↔ㅑ, ㅓ↔ㅕ, ㅗ↔ㅛ, ㅜ↔ㅠ 등)",
    "vflip": "상하 반전 — 1↔3, 4↔6 (ㅏ↔ㅓ, ㅑ↔ㅕ, ㅗ↔ㅜ, ㅛ↔ㅠ)",
    "rot180": "180° 회전 — 1↔6, 2↔5, 3↔4 (ㅏ↔ㅕ, ㅑ↔ㅓ, ㅗ↔ㅠ, ㅛ↔ㅜ)",
    "shift_down": "한 칸 아래로 — 1→2, 2→3, 4→5, 5→6 (초성→종성 ㄴㄷㅁㅋㅌㅍㅎ)",
    "shift_up": "한 칸 위로 — shift_down 의 역",
    "jamo_confusion": "다른 자모·약자의 점형을 찍음 (변환으로 설명되지 않음)",
    # 셀 열(단어 단위)
    "cell_omitted": "칸 누락 — 정답 칸 하나를 통째로 빼먹음",
    "cell_inserted": "칸 추가 — 정답에 없는 칸을 넣음",
    "transposition": "칸 순서 뒤바뀜 — 인접한 두 칸을 바꿔 찍음",
    # 규칙 단위
    "abbr_expanded": "약자 미사용 — 약자 자리를 풀어 씀 (예: '사' 약자 대신 ㅅ+ㅏ)",
    "abbr_overused": "약자 과잉 — 풀어 써야 할 자리에 약자 (제14항: 나이→ㄴㅏㅣ 인데 '나' 약자 사용)",
    "marker_missing": "표 누락 — 된소리표(6) 또는 구분표(36)를 빼먹음",
    "silent_onset_written": "초성 ㅇ 표기 — 첫소리 ㅇ은 적지 않는데(제1항 다만1) 그 자리에 셀을 넣음 (예: 음식을 6칸으로)",
}
SILENT_ONSET_KC = "규칙:초성ㅇ생략"
SILENT_ONSET_SHAPES = [
    [2, 3, 5, 6],
    [1, 2, 4, 5],
]  # 학습자가 'ㅇ'이라고 넣을 법한 점형: 받침 ㅇ, ㅇ 표기용(제1항 다만2)
GEOMETRIC = ("mirror", "vflip", "rot180", "shift_down", "shift_up")

_MAPS = {
    "mirror": {1: 4, 2: 5, 3: 6, 4: 1, 5: 2, 6: 3},
    "vflip": {1: 3, 2: 2, 3: 1, 4: 6, 5: 5, 6: 4},
    "rot180": {1: 6, 2: 5, 3: 4, 4: 3, 5: 2, 6: 1},
    "shift_down": {1: 2, 2: 3, 4: 5, 5: 6},
    "shift_up": {2: 1, 3: 2, 5: 4, 6: 5},
}


def transform(dots: frozenset[int] | list[int], name: str) -> frozenset[int] | None:
    """변환 적용. 적용 불가(이동인데 끝 줄에 점이 있음)면 None."""
    m = _MAPS[name]
    s = frozenset(dots)
    if any(d not in m for d in s):
        return None
    out = frozenset(m[d] for d in s)
    return None if out == s else out


# ── 2. 이름 있는 점형 목록 (무슨 자모로 읽히는지) ──────────────────────────
def _build_inventory() -> dict[frozenset[int], list[str]]:
    inv: dict[frozenset[int], list[str]] = defaultdict(list)

    def add(name: str, dots: str) -> None:
        inv[frozenset(int(x) for x in dots)].append(name)

    for k, v in kb.CHO_DOTS.items():
        add(f"초성:{k}", v)
    for k, v in kb.JONG_DOTS.items():
        add(f"종성:{k}", v)
    add("종성:ㅆ", "34")
    for k, v in kb.JUNG_DOTS.items():
        if len(v) == 1:
            add(f"중성:{k}", v[0])
    for k, v in kb.ABBR_A.items():
        add(f"약자:{k}ㅏ", v)
    for k, v in kb.ABBR_VC.items():
        add(f"약자:{kb.ABBR_VC_NAME[k]}", v)
    add("약자:것", kb.GEOT[0])
    add("규칙:된소리표", kb.DDN)
    add("규칙:구분표", kb.SEP)
    add("규칙:온표", "123456")
    return dict(inv)


INVENTORY = _build_inventory()


def reads_as(dots: list[int] | frozenset[int]) -> list[str]:
    """이 점형으로 읽힐 수 있는 자모/약자 이름들."""
    return INVENTORY.get(frozenset(dots), [])


# ── 3. 약자 풀어쓰기 표 (abbr_expanded 판정·생성용) ───────────────────────
def _expansions() -> dict[str, list[list[int]]]:
    ex: dict[str, list[list[int]]] = {}
    for k in kb.ABBR_A:
        ex[f"약자:{k}ㅏ"] = [[int(x) for x in kb.CHO_DOTS[k]], [1, 2, 6]]
    for (v, j), name in kb.ABBR_VC_NAME.items():
        ex[f"약자:{name}"] = [
            [int(x) for x in kb.JUNG_DOTS[v][0]],
            [int(x) for x in kb.JONG_DOTS[j]],
        ]
    ex["약자:영(성정청)"] = [[2, 3, 4], [2, 3, 5, 6]]
    ex["약자:것"] = [[4], [2, 3, 4], [3]]  # 2셀 약자 → 3셀 풀어쓰기 (첫 셀에 매핑)
    for w in kb.ABBR_WORDS:  # 제18항 약어(2셀) → 풀어쓰기
        ex[f"약어:{w}"] = [c.dots for c in kb.convert(w, use_word_abbr=False)]
    return ex


EXPANSIONS = _expansions()


def word_abbr_expansion(word: str) -> list[list[int]]:
    """제18항 약어 단어의 풀어쓰기 셀열."""
    return [c.dots for c in kb.convert(word, use_word_abbr=False)]


# ── 4. 셀 하나 판정 ────────────────────────────────────────────────────────
def classify_cell(expected: list[int], actual: list[int]) -> tuple[str | None, dict[str, Any]]:
    """정답 셀 vs 입력 셀 → (유형, 상세). 동일하면 (None, {})."""
    e, a = frozenset(expected), frozenset(actual)
    if e == a:
        return None, {}
    detail: dict[str, Any] = {
        "missing": sorted(e - a),
        "extra": sorted(a - e),
        "reads_as": reads_as(a),
    }
    geo = [name for name in GEOMETRIC if transform(e, name) == a]
    if geo:  # 대칭 점형은 여러 변환이 동시에 성립할 수 있다 → 모두 기록
        detail["transforms"] = geo
        return geo[0], detail
    if a < e:
        return "dot_missing", detail
    if a > e:
        return "dot_extra", detail
    if detail["reads_as"]:
        return "jamo_confusion", detail
    return "dot_mixed", detail


# ── 5. 셀 열 정렬 + 단어 단위 판정 (채점기 핵심) ────────────────────────────
def align(expected: Cells, actual: Cells) -> list[tuple[str, int | None, int | None]]:
    """편집 거리 정렬. 반환: [(op, i, j)]  op ∈ match/sub/del/ins, i=정답 인덱스, j=입력 인덱스."""
    exp = [frozenset(c) for c in expected]
    act = [frozenset(c) for c in actual]
    n, m = len(exp), len(act)
    dist = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dist[i][0] = i
    for j in range(1, m + 1):
        dist[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            sub = dist[i - 1][j - 1] + (0 if exp[i - 1] == act[j - 1] else 1)
            dist[i][j] = min(sub, dist[i - 1][j] + 1, dist[i][j - 1] + 1)
    ops: list[tuple[str, int | None, int | None]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if (
            i > 0
            and j > 0
            and dist[i][j] == dist[i - 1][j - 1] + (0 if exp[i - 1] == act[j - 1] else 1)
        ):
            ops.append(("match" if exp[i - 1] == act[j - 1] else "sub", i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i > 0 and dist[i][j] == dist[i - 1][j] + 1:
            ops.append(("del", i - 1, None))
            i -= 1
        else:
            ops.append(("ins", None, j - 1))
            j -= 1
    return ops[::-1]


def diagnose(
    expected: Cells,
    kcs: list[str],
    actual: Cells,
    rules: list[str | None] | None = None,
    syllables: list[dict[str, Any]] | None = None,
) -> list[Error]:
    """정답 셀열·KC 태그·입력 셀열 → 오류 목록.
    각 오류: {type, cell(정답 인덱스), kc, expected, actual, detail}
    규칙 단위 오류(abbr_expanded/overused, marker_missing, transposition, silent_onset_written)를 먼저 잡고
    나머지를 셀 단위로 판정한다. syllables(kbraille.analyze 의 것)가 있으면 초성 ㅇ 자리 삽입을 규칙 오류로 귀속한다.
    """
    rules_: list[str | None] = list(rules) if rules else [None] * len(expected)
    errors: list[Error] = []
    ops = align(expected, actual)
    used: set[int] = set()
    # 초성 ㅇ 음절의 첫 셀 인덱스 (그 앞에 삽입된 셀 = 'ㅇ을 적었다')
    onset_pos: dict[int, int] = {}
    if syllables:
        for s in syllables:
            if s.get("cho") == "ㅇ":
                first = s["cells"][0] if s["cells"] else len(expected)
                onset_pos[first] = s["idx"]

    # (a) 전위: 인접 sub-sub 가 서로 바뀐 경우
    for k in range(len(ops) - 1):
        op1, i1, j1 = ops[k]
        op2, i2, j2 = ops[k + 1]
        if i1 is None or i2 is None or j1 is None or j2 is None:
            continue
        if (
            op1 == op2 == "sub"
            and i1 + 1 == i2
            and frozenset(expected[i1]) == frozenset(actual[j2])
            and frozenset(expected[i2]) == frozenset(actual[j1])
        ):
            errors.append(_err("transposition", i1, kcs, expected, [actual[j1], actual[j2]]))
            used.update({k, k + 1})

    # (b) 약자 풀어쓰기: 약자가 차지하는 정답 셀(1~2개)에 대응하는 op 들과 그 앞뒤의 ins 를 하나의 창으로 보고,
    #     창 안의 입력 셀열 어딘가에 풀어쓰기 열이 있으면 abbr_expanded
    for i, kc in enumerate(kcs):
        if not kc.startswith(("약자:", "약어:")) or kc not in EXPANSIONS:
            continue
        if (
            i > 0
            and kcs[i - 1] == kc
            and kc in ("약자:것",) + tuple(f"약어:{w}" for w in kb.ABBR_WORDS)
        ):
            continue  # 다중 셀 약자·약어의 두 번째 셀은 첫 셀에서 처리
        m = 1
        while (
            i + m < len(kcs)
            and kcs[i + m] == kc
            and kc in ("약자:것",) + tuple(f"약어:{w}" for w in kb.ABBR_WORDS)
        ):
            m += 1
        idx = [k for k, (op, ei, _) in enumerate(ops) if ei is not None and i <= ei < i + m]
        if not idx or any(k in used for k in idx):
            continue
        lo, hi = idx[0], idx[-1]
        while lo - 1 >= 0 and ops[lo - 1][0] == "ins" and (lo - 1) not in used:
            lo -= 1
        while hi + 1 < len(ops) and ops[hi + 1][0] == "ins" and (hi + 1) not in used:
            hi += 1
        win = [(k, actual[j]) for k in range(lo, hi + 1) if (j := ops[k][2]) is not None]
        exp_cells = [frozenset(c) for c in EXPANSIONS[kc]]
        width = len(exp_cells)
        for s in range(len(win) - width + 1):
            if [frozenset(c) for _, c in win[s : s + width]] == exp_cells:
                errors.append(
                    _err("abbr_expanded", i, kcs, expected, [c for _, c in win[s : s + width]])
                )
                used.update(k for k, _ in win[s : s + width])
                used.update(range(lo, hi + 1))  # 창 안의 정답 셀 op(sub/match/del)도 소진
                break

    # (b2) 초성 ㅇ 표기: 아직 설명되지 않은 ins 가 ㅇ 음절 첫 셀 바로 앞에 있고, 넣은 점형이 'ㅇ'으로 볼 만할 때
    for k, (op, _i, j) in enumerate(ops):
        if op != "ins" or k in used or j is None:
            continue
        if sorted(actual[j]) not in SILENT_ONSET_SHAPES and "종성:ㅇ" not in reads_as(actual[j]):
            continue
        nxt = next(
            (ops[t][1] for t in range(k + 1, len(ops)) if ops[t][1] is not None), len(expected)
        )
        if nxt in onset_pos:
            errors.append(
                {
                    "type": "silent_onset_written",
                    "cell": nxt,
                    "kc": SILENT_ONSET_KC,
                    "expected": None,
                    "actual": actual[j],
                    "detail": {"syl": onset_pos[nxt], "reads_as": reads_as(actual[j])},
                }
            )
            used.add(k)

    # (c) 나머지 op 처리
    for k, (op, i, j) in enumerate(ops):
        if k in used or op == "match":
            continue
        if op == "del":
            assert i is not None
            kc = kcs[i]
            syl_of = next((s["idx"] for s in (syllables or []) if i in s["cells"]), None)
            if kc.startswith("규칙:"):
                e = _err("marker_missing", i, kcs, expected, None)
                e["detail"] = {"syl": syl_of}
                errors.append(e)
            elif rules_[i] == "제14항" and kc == "중성:ㅏ":
                e = _err("abbr_overused", i, kcs, expected, None)
                e["detail"] = {"syl": syl_of}
                if syllables:
                    e["kc"] = "규칙:약자미사용"
                errors.append(e)
            else:
                errors.append(_err("cell_omitted", i, kcs, expected, None))
        elif op == "ins":
            assert j is not None
            errors.append(
                {
                    "type": "cell_inserted",
                    "cell": None,
                    "kc": None,
                    "expected": None,
                    "actual": actual[j],
                    "detail": {"reads_as": reads_as(actual[j])},
                }
            )
        else:  # sub
            assert i is not None and j is not None
            t, d = classify_cell(expected[i], actual[j])
            e = _err(t, i, kcs, expected, actual[j])
            e["detail"] = d
            errors.append(e)
    return errors


def _err(t: str | None, i: int, kcs: list[str], expected: Cells, actual: Any) -> Error:
    return {
        "type": t,
        "cell": i,
        "kc": kcs[i],
        "expected": expected[i],
        "actual": actual,
        "detail": {},
    }


def is_correct(expected: Cells, actual: Cells) -> bool:
    return [dots_to_int(c) for c in expected] == [dots_to_int(c) for c in actual]


# ── 6. 오답 후보 생성 (시뮬레이터 핵심) ────────────────────────────────────
def candidate_errors(
    cells: Cells,
    kcs: list[str],
    rules: list[str | None],
    word: str | None = None,
    syllables: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """정답 셀열에 대해 '학습자가 낼 법한' 오답 후보 목록.
    반환: [{type, cell, kc, actual_cells, (repl)}]  — actual_cells 는 오답 전체 셀열.
    가중치는 시뮬레이터가 학습자 성향·숙련도로 부여한다.
    """
    out: list[dict[str, Any]] = []
    n = len(cells)
    if syllables:
        for s in syllables:
            if s.get("cho") == "ㅇ" and s["cells"]:
                i = s["cells"][0]
                for shape in SILENT_ONSET_SHAPES:
                    out.append(
                        {
                            "type": "silent_onset_written",
                            "cell": i,
                            "kc": SILENT_ONSET_KC,
                            "actual_cells": cells[:i] + [shape] + cells[i:],
                            "repl": [shape, cells[i]],
                        }
                    )
    for i, (c, kc) in enumerate(zip(cells, kcs, strict=True)):
        s = frozenset(c)
        # 기하 변환
        for name in GEOMETRIC:
            t = transform(s, name)
            if t is not None:
                out.append(_cand(name, i, kc, cells, [sorted(t)]))
        # 점 누락 / 추가
        for d in s:
            if len(s) > 1:
                out.append(_cand("dot_missing", i, kc, cells, [sorted(s - {d})]))
        for d in range(1, 7):
            if d not in s:
                out.append(_cand("dot_extra", i, kc, cells, [sorted(s | {d})]))
        # 다른 자모와 혼동 (해밍 거리 ≤ 2, 변환·누락·추가로 이미 설명되는 것은 제외)
        for other, names in INVENTORY.items():
            if other == s or len(other ^ s) > 2 or other < s or other > s:
                continue
            if any(transform(s, g) == other for g in GEOMETRIC):
                continue
            out.append(_cand("jamo_confusion", i, kc, cells, [sorted(other)], reads=names))
        # 약자 풀어쓰기
        if kc in EXPANSIONS and kc != "약자:것":
            out.append(_cand("abbr_expanded", i, kc, cells, EXPANSIONS[kc]))
        if kc == "약자:것" and i + 1 < n and kcs[i + 1] == "약자:것":
            out.append(
                {
                    "type": "abbr_expanded",
                    "cell": i,
                    "kc": kc,
                    "actual_cells": cells[:i] + EXPANSIONS[kc] + cells[i + 2 :],
                }
            )
        # 약자 과잉(제14항) / 표 누락 / 칸 누락
        if rules[i] == "제14항" and kc == "중성:ㅏ":
            out.append(_cand("abbr_overused", i, "규칙:약자미사용" if syllables else kc, cells, []))
        elif kc.startswith("규칙:"):
            out.append(_cand("marker_missing", i, kc, cells, []))
        elif n > 1:
            out.append(_cand("cell_omitted", i, kc, cells, []))
    # 전위
    for i in range(n - 1):
        if frozenset(cells[i]) != frozenset(cells[i + 1]):
            out.append(
                {
                    "type": "transposition",
                    "cell": i,
                    "kc": kcs[i],
                    "actual_cells": cells[:i] + [cells[i + 1], cells[i]] + cells[i + 2 :],
                }
            )
    # 약어 풀어쓰기
    if word and kcs and kcs[0].startswith("약어:"):
        out.append(
            {
                "type": "abbr_expanded",
                "cell": 0,
                "kc": kcs[0],
                "actual_cells": word_abbr_expansion(word),
            }
        )
    # 중복 제거 + 유형 이름을 판정 결과로 통일 (대칭 점형은 mirror/rot180 등이 동시에 성립하므로)
    seen: dict[tuple[tuple[int, ...], ...], dict[str, Any]] = {}
    for c in out:
        key = tuple(tuple(sorted(x)) for x in c["actual_cells"])
        if key in seen or key == tuple(tuple(sorted(x)) for x in cells):
            continue
        d = diagnose(cells, kcs, c["actual_cells"], rules, syllables)
        hit = [e for e in d if e["cell"] == c["cell"]] or d
        if hit:
            c["type"] = hit[0]["type"]
            c["all_types"] = sorted({e["type"] for e in d})
        seen[key] = c
    return list(seen.values())


def _cand(
    t: str, i: int, kc: str, cells: Cells, repl: Cells, reads: list[str] | None = None
) -> dict[str, Any]:
    d = {
        "type": t,
        "cell": i,
        "kc": kc,
        "actual_cells": cells[:i] + repl + cells[i + 1 :],
        "repl": repl,
    }
    if reads:
        d["reads_as"] = reads
    return d
