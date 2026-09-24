"""백엔드(Spring FastApiClient) 전용 라우터 — 루트 경로, 래퍼 없음.

RestClient.baseUrl = AI_BASE_URL(기본 http://localhost:8000), uri("/analyze") / uri("/reviewAnalyze").
응답 몸통을 그대로 ProblemAnalyzeResponse / ReviewAnalyzeResponse 로 읽으므로 {success,data} 로 감싸지 않는다.
필드명은 Java record 컴포넌트명과 동일(camelCase, errorCounts 키만 snake_case).
"""

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.application.backend_services import (
    ProblemAnalyzeService,
    ReviewAnalyzeService,
    UnsupportedTextError,
)
from app.infrastructure.braille_core.estimate import default_estimator
from app.infrastructure.rule_engine import get_skill_catalog

router = APIRouter(tags=["backend"])

ERROR_TYPES = (
    "dot_missing",
    "dot_extra",
    "dot_mixed",
    "mirror",
    "vflip",
    "rot180",
    "shift_down",
    "shift_up",
    "jamo_confusion",
    "cell_omitted",
    "cell_inserted",
    "transposition",
    "abbr_expanded",
    "abbr_overused",
    "marker_missing",
    "silent_onset_written",
)


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


# ── /analyze ─────────────────────────────────────────────────────────────────
class ProblemAnalyzeRequest(CamelModel):
    """Java RegisterProblemRequest(String targetText)"""

    target_text: str = Field(min_length=1, max_length=50)


class SkillSetSchema(CamelModel):
    skill_type: str
    metadata: str


class ProblemAnalyzeResponse(CamelModel):
    """Java ProblemAnalyzeResponse(Level level, List<Integer> cells, List<SkillSet> skillSets)"""

    level: str
    cells: list[int]
    skill_sets: list[SkillSetSchema]


# ── /reviewAnalyze ───────────────────────────────────────────────────────────
class ErrorCountSchema(BaseModel):
    """Java ErrorCount — 컴포넌트명이 snake_case 라 alias 없이 그대로. 빠진 키는 0."""

    model_config = ConfigDict(extra="ignore")
    dot_missing: int = 0
    dot_extra: int = 0
    dot_mixed: int = 0
    mirror: int = 0
    vflip: int = 0
    rot180: int = 0
    shift_down: int = 0
    shift_up: int = 0
    jamo_confusion: int = 0
    cell_omitted: int = 0
    cell_inserted: int = 0
    transposition: int = 0
    abbr_expanded: int = 0
    abbr_overused: int = 0
    marker_missing: int = 0
    silent_onset_written: int = 0


class SkillStateSchema(CamelModel):
    """Java SkillState(Long learningSkillId, Integer nAttempts, Integer nCorrect,
    LocalDateTime lastPracticedAt, Double masteryProb, ErrorCount errorCounts)"""

    learning_skill_id: int
    n_attempts: int = 0
    n_correct: int = 0
    last_practiced_at: datetime | None = None  # LocalDateTime(타임존 없음) → UTC 로 해석
    mastery_prob: float | None = None
    error_counts: ErrorCountSchema | None = None


class ReviewAnalyzeRequest(CamelModel):
    """Java ReviewAnalyzeRequest(List<SkillState> skillStates)"""

    skill_states: list[SkillStateSchema] = Field(default_factory=list)


class ReviewAnalyzeResponse(CamelModel):
    """Java ReviewAnalyzeResponse(Level level, List<Long> learningSkillIds)"""

    level: str
    learning_skill_ids: list[int]


# ── DI ───────────────────────────────────────────────────────────────────────
def get_problem_service() -> ProblemAnalyzeService:
    return ProblemAnalyzeService(catalog=get_skill_catalog())


def get_review_service() -> ReviewAnalyzeService:
    return ReviewAnalyzeService(catalog=get_skill_catalog(), estimator=default_estimator())


# ── endpoints ────────────────────────────────────────────────────────────────
@router.post("/analyze", response_model=ProblemAnalyzeResponse, response_model_by_alias=True)
def analyze_problem(
    request: ProblemAnalyzeRequest,
    service: ProblemAnalyzeService = Depends(get_problem_service),
) -> ProblemAnalyzeResponse:
    try:
        result = service.analyze(request.target_text)
    except UnsupportedTextError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    return ProblemAnalyzeResponse(
        level=result.level,
        cells=result.cells,
        skill_sets=[
            SkillSetSchema(skill_type=s.skill_type, metadata=s.metadata) for s in result.skill_sets
        ],
    )


@router.post("/reviewAnalyze", response_model=ReviewAnalyzeResponse, response_model_by_alias=True)
def analyze_review(
    request: ReviewAnalyzeRequest,
    service: ReviewAnalyzeService = Depends(get_review_service),
) -> ReviewAnalyzeResponse:
    states: list[dict[str, Any]] = []
    for s in request.skill_states:
        errs = s.error_counts.model_dump() if s.error_counts else {}
        states.append(
            {
                "learning_skill_id": s.learning_skill_id,
                "n_attempts": s.n_attempts,
                "n_correct": s.n_correct,
                "last_practice": s.last_practiced_at.replace(tzinfo=None).isoformat()
                if s.last_practiced_at
                else None,
                "mastery_prob": s.mastery_prob,
                "n_errors_by_type": {k: v for k, v in errs.items() if v},
            }
        )
    result = service.analyze(states)
    return ReviewAnalyzeResponse(level=result.level, learning_skill_ids=result.learning_skill_ids)
