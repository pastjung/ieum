"""presentation 계층 DTO (pydantic) — 도메인 모델을 API 스키마로 변환한다."""

from pydantic import BaseModel, Field

from app.domain.braille.models import ConversionResult


class ConvertRequest(BaseModel):
    text: str = Field(min_length=1, max_length=200, description="변환할 한글 텍스트")


class CellSchema(BaseModel):
    char: str
    patterns: list[int]
    rule: str


class ConvertResponse(BaseModel):
    engine_version: str = Field(serialization_alias="engineVersion")
    rule_version: str = Field(serialization_alias="ruleVersion")
    cells: list[CellSchema]

    @classmethod
    def from_domain(cls, result: ConversionResult) -> "ConvertResponse":
        return cls(
            engine_version=result.engine_version,
            rule_version=result.rule_version,
            cells=[
                CellSchema(
                    char=c.char,
                    patterns=[cell.pattern for cell in c.cells],
                    rule=c.rule,
                )
                for c in result.chars
            ],
        )


class ApiResponse[T](BaseModel):
    """공통 응답 래퍼 (API 명세서 0-3)"""

    success: bool = True
    data: T | None = None
