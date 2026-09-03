"""도메인 모델 — 점자 Cell Value Object와 변환 결과 엔티티.

점자 셀 1개 = 6bit 정수(0~63). 1~6번 점이 bit 0~5에 대응한다. (API 명세서 0-2)
도메인 계층은 FastAPI/외부 라이브러리에 의존하지 않는다.
"""

from dataclasses import dataclass

MIN_PATTERN = 0
MAX_PATTERN = 63


@dataclass(frozen=True)
class BrailleCell:
    """6bit 점자 패턴 Value Object. 잘못된 범위의 셀은 생성 자체가 불가능하다."""

    pattern: int

    def __post_init__(self) -> None:
        if not (MIN_PATTERN <= self.pattern <= MAX_PATTERN):
            raise ValueError(f"패턴 값이 허용 범위({MIN_PATTERN}~{MAX_PATTERN})를 벗어났습니다: {self.pattern}")

    @property
    def dots(self) -> list[int]:
        """켜져 있는 점 번호 목록 (1~6)"""
        return [i + 1 for i in range(6) if self.pattern & (1 << i)]


@dataclass(frozen=True)
class ConvertedChar:
    """글자 하나의 변환 결과"""

    char: str
    cells: tuple[BrailleCell, ...]
    rule: str  # 정자 / 약자 / 약어 / 숫자 ...


@dataclass(frozen=True)
class ConversionResult:
    """텍스트 전체의 변환 결과 (애그리거트)"""

    text: str
    chars: tuple[ConvertedChar, ...]
    engine_version: str
    rule_version: str
