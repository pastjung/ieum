"""6점 점자 셀 표현 규약 — 점 번호 리스트 ⟷ 0~63 정수 ⟷ 유니코드 (API 명세서 0-2).

입력 UI·채점·촉지 모듈이 같은 정수를 쓴다. 점 n = 비트 n-1.
"""

from collections.abc import Iterable, Sequence

BRAILLE_BASE = 0x2800
MAX_PATTERN = 0b111111


def dots_to_int(dots: Iterable[int]) -> int:
    return sum(1 << (d - 1) for d in dots)


def int_to_dots(value: int) -> list[int]:
    return [i + 1 for i in range(6) if value & (1 << i)]


def str_to_dots(s: str) -> list[int]:
    """'126' → [1, 2, 6]"""
    return [int(ch) for ch in s]


def dots_to_cell(dots: Iterable[int]) -> str:
    return chr(BRAILLE_BASE + dots_to_int(dots))


def cells_to_unicode(cells: Sequence[Sequence[int]]) -> str:
    return "".join(dots_to_cell(c) if c else " " for c in cells)
