"""도메인 포트 — 변환 엔진 인터페이스.

실제 구현(규칙 테이블, ML 모델)은 infrastructure 계층에서 제공한다 (의존성 역전).
"""

from typing import Protocol

from app.domain.braille.models import ConversionResult


class BrailleConverter(Protocol):
    def convert(self, text: str) -> ConversionResult:
        """한글 텍스트를 6bit 점자 Cell 배열로 변환한다."""
        ...


class RuleValidator(Protocol):
    def validate(self, result: ConversionResult) -> bool:
        """변환 결과가 공식 점자 규칙과 일치하는지 검증한다."""
        ...
