from fastapi import APIRouter, Depends, HTTPException

from app.application.convert_service import ConversionFailedError, ConvertService
from app.infrastructure.rule_engine import RuleBrailleConverter, RuleValidatorByRoundTrip
from app.presentation.api.v1.schemas import ApiResponse, ConvertRequest, ConvertResponse

router = APIRouter(prefix="/convert", tags=["convert"])


def get_convert_service() -> ConvertService:
    """의존성 주입 — infrastructure 구현체를 여기서만 조립한다."""
    return ConvertService(converter=RuleBrailleConverter(), validator=RuleValidatorByRoundTrip())


@router.post("/braille", response_model=ApiResponse[ConvertResponse], response_model_by_alias=True)
def convert_braille(
    request: ConvertRequest,
    service: ConvertService = Depends(get_convert_service),
) -> ApiResponse[ConvertResponse]:
    try:
        result = service.convert_text(request.text)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except ConversionFailedError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    return ApiResponse(data=ConvertResponse.from_domain(result))
