"""점자 사전 — 텍스트를 점자 칸과 칸별 요소 이름으로 바꾼다 (상태 없음, 저장 없음).

'가방'      → ⠫⠘⠶  [약자 가][약자 바][받침 ㅇ]
'학교 가방' → 단어 사이에 빈 칸(띄어쓰기) 한 칸
'ㄱ'        → 낱자는 첫소리로 (drill_items 와 같은 규칙)
"""

from typing import Any

from app.application.session_services import label_cells
from app.domain.skills.models import SkillCatalog
from app.infrastructure.braille_core import kbraille
from app.infrastructure.braille_core.braille_utils import BRAILLE_BASE, dots_to_int
from app.infrastructure.braille_core.drill_items import build_drill_item

SPACE_LABEL = "띄어쓰기"


def _word_cells(word: str) -> tuple[list[list[int]], list[str], list[int | None]]:
    item = build_drill_item(word)
    if item is not None:
        return item["cells"], item["kc"], [None] * len(item["cells"])
    an = kbraille.analyze(word)  # 순수 한글이 아니면 ValueError
    syls: list[int | None] = [c.syl for c in an.cells]
    return [c.dots for c in an.cells], [c.kc for c in an.cells], syls


def lookup_braille(text: str, catalog: SkillCatalog) -> dict[str, Any]:
    words = text.split()
    if not words:
        raise ValueError("검색어가 비어 있어요")
    cells: list[dict[str, Any]] = []
    for w_i, word in enumerate(words):
        if w_i > 0:
            cells.append({"pattern": 0, "learning_skill_id": None, "label": SPACE_LABEL})
        dots, kcs, syls = _word_cells(word)
        for d, lab in zip(dots, label_cells(kcs, syls, catalog), strict=True):
            cells.append(
                {
                    "pattern": dots_to_int(d),
                    "learning_skill_id": lab.learning_skill_id,
                    "label": lab.label,
                }
            )
    for i, c in enumerate(cells, 1):
        c["cell_index"] = i
    braille = "".join(chr(BRAILLE_BASE + c["pattern"]) for c in cells)
    return {"text": " ".join(words), "braille": braille, "cells": cells}
