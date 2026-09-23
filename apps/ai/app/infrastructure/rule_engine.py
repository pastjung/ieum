"""규칙 엔진 구현체 — 도메인 포트(BrailleConverter, SkillCatalog)를 kbraille 로 구현한다.

데이터 파일
  data/kc_catalog.json          학습 요소 100개 (id·kc·skill_type·metadata·stage·level·dots) — SQL 시드와 같은 원천
  data/level_skill_counts.json  level 별 문항 수, (level, skill_id) 별 그 요소를 포함한 시드 문항 수 (복습 level 결정용 하한)
"""

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from app.core.config import get_settings
from app.domain.braille.models import BrailleCell, ConversionResult, ConvertedChar
from app.domain.skills.models import LearningSkill
from app.infrastructure.braille_core import kbraille

_DATA = Path(__file__).parent / "data"


class JsonSkillCatalog:
    """SkillCatalog 포트 구현 — 파일 한 번 읽어 메모리에 둔다."""

    def __init__(self, catalog_path: Path | None = None, counts_path: Path | None = None) -> None:
        raw: dict[str, Any] = json.loads(
            (catalog_path or _DATA / "kc_catalog.json").read_text(encoding="utf-8")
        )
        self._skills: list[LearningSkill] = [
            LearningSkill(
                id=int(r["id"]),
                kc=str(r["kc"]),
                skill_type=str(r["skill_type"]),
                metadata=str(r["metadata"]),
                display_name=str(r["display_name"]),
                stage=str(r["stage"]),
                level=str(r["level"]),
            )
            for r in raw["kcs"]
        ]
        self._by_kc = {s.kc: s for s in self._skills}
        self._by_id = {s.id: s for s in self._skills}
        counts: dict[str, Any] = json.loads(
            (counts_path or _DATA / "level_skill_counts.json").read_text(encoding="utf-8")
        )
        self._level_totals: dict[str, int] = {k: int(v) for k, v in counts["level_totals"].items()}
        self._counts: dict[str, dict[int, int]] = {
            level: {int(sid): int(n) for sid, n in per.items()}
            for level, per in counts["counts"].items()
        }

    def by_kc(self, kc: str) -> LearningSkill:
        return self._by_kc[kc]

    def by_id(self, skill_id: int) -> LearningSkill | None:
        return self._by_id.get(skill_id)

    def all(self) -> list[LearningSkill]:
        return list(self._skills)

    def problem_count(self, level: str, skill_ids: list[int]) -> int:
        # 합집합의 정확한 크기는 알 수 없으므로 '가장 많은 요소 하나'를 하한으로 쓴다 (보수적)
        per = self._counts.get(level, {})
        return max((per.get(sid, 0) for sid in skill_ids), default=0)

    def level_total(self, level: str) -> int:
        return self._level_totals.get(level, 0)


@lru_cache
def get_skill_catalog() -> JsonSkillCatalog:
    return JsonSkillCatalog()


class RuleBrailleConverter:
    """BrailleConverter 포트 구현 — 한국 점자 규정(2024) 한글 제1~18항 규칙 엔진."""

    def convert(self, text: str) -> ConversionResult:
        settings = get_settings()
        chars: list[ConvertedChar] = []
        for token in _split_keep_spaces(text):
            if token == " ":
                chars.append(ConvertedChar(char=" ", cells=(BrailleCell(0),), rule="공백"))
                continue
            cells = kbraille.convert(token)
            # 음절별로 묶어 ConvertedChar 로 (약어는 한 덩어리)
            by_syl: dict[int, list[BrailleCell]] = {}
            rules: dict[int, set[str]] = {}
            for c in cells:
                by_syl.setdefault(c.syl, []).append(BrailleCell(c.value))
                rules.setdefault(c.syl, set()).add(c.kc.split(":")[0])
            for i, ch in enumerate(token):
                if i in by_syl:
                    chars.append(
                        ConvertedChar(
                            char=ch, cells=tuple(by_syl[i]), rule="·".join(sorted(rules[i]))
                        )
                    )
                elif i > 0 and cells and cells[0].kc.startswith("약어"):
                    continue  # 약어 뒷글자는 첫 글자 셀에 포함됨
        return ConversionResult(
            text=text,
            chars=tuple(chars),
            engine_version=settings.engine_version,
            rule_version=settings.rule_version,
        )


def _split_keep_spaces(text: str) -> list[str]:
    out: list[str] = []
    buf = ""
    for ch in text:
        if ch == " ":
            if buf:
                out.append(buf)
                buf = ""
            out.append(" ")
        else:
            buf += ch
    if buf:
        out.append(buf)
    return out


class RuleValidatorByRoundTrip:
    """RuleValidator 포트 구현 — 변환 결과 셀이 0~63 범위이고 비어 있지 않은지만 확인(규칙 엔진 자체가 정답 원천)."""

    def validate(self, result: ConversionResult) -> bool:
        return all(0 <= cell.pattern <= 63 for ch in result.chars for cell in ch.cells) and bool(
            result.chars
        )
