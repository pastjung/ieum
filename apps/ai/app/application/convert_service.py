"""애플리케이션 서비스 — '변환 후 규칙 검증' 유스케이스.

변환 모델 결과를 규칙 엔진으로 검증하고, 통과한 결과만 정답 기준으로 내보낸다. (기능명세 4.3)
"""

from dataclasses import dataclass

from app.domain.braille.models import ConversionResult
from app.domain.braille.ports import BrailleConverter, RuleValidator


class ConversionFailedError(Exception):
    """검증 불일치 등으로 변환 결과를 신뢰할 수 없을 때"""


@dataclass
class ConvertService:
    converter: BrailleConverter
    validator: RuleValidator

    def convert_text(self, text: str) -> ConversionResult:
        if not text or not text.strip():
            raise ValueError("변환할 텍스트가 비어 있습니다.")

        result = self.converter.convert(text)

        if not self.validator.validate(result):
            # 모델 결과와 규칙 엔진 결과 불일치 → 출제 중단, 검토 대상 분류
            raise ConversionFailedError("규칙 검증에 실패한 변환 결과입니다.")

        return result
