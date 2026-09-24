"""캡슐 NLU 디버깅용 API.

앱을 켜지 않고도 "이 발화를 어떻게 알아듣는지" 확인할 수 있다.
"""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.application.nl_training_service import NlTrainingService
from app.domain.nl.evaluator import extract_concepts
from app.infrastructure.nl_model_store import JsonNlModelStore

router = APIRouter(prefix="/nl", tags=["nl"])

_store = JsonNlModelStore()


class ParseRequest(BaseModel):
    text: str = Field(..., examples=["복습 열 문제 시작해줘"])


class ParseResponse(BaseModel):
    text: str
    action: Optional[str]
    confidence: float
    accepted: bool
    concepts: dict[str, str]


class TrainRequest(BaseModel):
    min_confidence: float = 0.55
    alpha: float = 0.3
    export: bool = True


@router.post("/parse", response_model=ParseResponse)
def parse(request: ParseRequest) -> ParseResponse:
    model = _store.load()
    if model is None:
        raise HTTPException(status_code=409, detail="학습된 모델이 없습니다. scripts/train_nl.py 를 먼저 실행하세요.")

    prediction = model.classify(request.text)
    confidence = prediction.confidence if prediction else 0.0
    accepted = prediction is not None and confidence >= model.min_confidence

    return ParseResponse(
        text=request.text,
        action=prediction.label if (prediction and accepted) else None,
        confidence=round(confidence, 4),
        accepted=accepted,
        concepts=extract_concepts(request.text, model.concepts),
    )


@router.post("/train")
def train(request: TrainRequest) -> dict:
    outcome = NlTrainingService(store=_store).run(
        alpha=request.alpha,
        min_confidence=request.min_confidence,
        export=request.export,
    )
    return {
        "success": True,
        "data": {
            "trainSize": outcome.train_size,
            "holdoutSize": outcome.holdout_size,
            "labels": outcome.model.labels,
            "reports": [
                {
                    "name": report.name,
                    "total": report.total,
                    "correct": report.correct,
                    "accuracy": round(report.accuracy, 4),
                }
                for report in outcome.reports
            ],
            "exported": [str(path) for path in outcome.exported],
        },
    }
