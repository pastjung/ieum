"""변환 엔진 스텁 구현.

TODO: 국립국어원 말뭉치 기반 변환 모델 + 공식 규칙 엔진으로 교체한다 (기능명세 4.2~4.3).
지금은 파이프라인 배선 확인용으로 극히 일부 자모만 매핑한다.
"""

from app.core.config import get_settings
from app.domain.braille.models import BrailleCell, ConversionResult, ConvertedChar

# 한국 점자 일부 예시 매핑 (자리표시용 — 실제 규칙 엔진으로 교체 예정)
_SAMPLE_TABLE: dict[str, tuple[list[int], str]] = {
    "가": ([16, 21], "정자"),
    "나": ([4, 21], "정자"),
    "다": ([24, 21], "정자"),
}


class StubBrailleConverter:
    """BrailleConverter 포트의 임시 구현체"""

    def convert(self, text: str) -> ConversionResult:
        settings = get_settings()
        chars: list[ConvertedChar] = []

        for ch in text:
            if ch == " ":
                chars.append(ConvertedChar(char=ch, cells=(BrailleCell(0),), rule="공백"))
                continue
            patterns, rule = _SAMPLE_TABLE.get(ch, ([0], "미지원(스텁)"))
            chars.append(
                ConvertedChar(char=ch, cells=tuple(BrailleCell(p) for p in patterns), rule=rule)
            )

        return ConversionResult(
            text=text,
            chars=tuple(chars),
            engine_version=settings.engine_version,
            rule_version=settings.rule_version,
        )


class AlwaysPassValidator:
    """RuleValidator 포트의 임시 구현체 — 규칙 엔진 연동 전까지 통과 처리"""

    def validate(self, result: ConversionResult) -> bool:
        return True
