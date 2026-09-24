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
from app.application.session_services import Attempt, SessionService, SkillState
from app.infrastructure.braille_core.estimate import BKTEstimator, default_estimator
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
    """Java ReviewAnalyzeResponse(List<Long> learningSkillIds) — level 없음(문항 풀은 Spring 이 사용자 레벨로 결정)"""

    learning_skill_ids: list[int]


# ── /attempt-analyze, /session-summary (2026-09-25 확정) ───────────────────
class AttemptSchema(CamelModel):
    target_text: str = Field(min_length=1, max_length=50)
    expected_patterns: list[int] = Field(default_factory=list)
    input_patterns: list[int] = Field(default_factory=list, max_length=12)


class AttemptAnalyzeRequest(CamelModel):
    """{ attempt: {targetText, expectedPatterns, inputPatterns}, skillStates: [] }"""

    attempt: AttemptSchema
    skill_states: list[SkillStateSchema] = Field(default_factory=list)


class FeedbackSchema(CamelModel):
    tts: str
    detail: str


class SkillResultSchema(CamelModel):
    """이 문항의 요소별 정오. errorCounts 는 이번 문항에서 낸 오류만(맞으면 빈 객체)."""

    learning_skill_id: int
    correct: bool
    error_counts: dict[str, int]


class SkillStateOutSchema(CamelModel):
    learning_skill_id: int
    n_attempts: int
    n_correct: int
    last_practiced_at: str  # LocalDateTime 형식(Z 없음), UTC
    mastery_prob: float
    error_counts: dict[str, int]


class AttemptAnalyzeResponse(CamelModel):
    """{ feedback, skillResults[], updatedSkillStates[] }  — updatedSkillStates 는 이번 문항에 나온 요소만(변경분)"""

    feedback: FeedbackSchema
    skill_results: list[SkillResultSchema]
    updated_skill_states: list[SkillStateOutSchema]


class SessionSummaryRequest(CamelModel):
    """{ attempts: [...], skillStates: [] } — skillStates 는 마지막 attempt-analyze 의 updatedSkillStates 를 반영한 현재 상태"""

    attempts: list[AttemptSchema] = Field(default_factory=list, max_length=50)
    skill_states: list[SkillStateSchema] = Field(default_factory=list)


class WeakSkillSchema(CamelModel):
    learning_skill_id: int
    mastery_prob: float
    label: str
    dominant_error_type: str | None
    explanation: str


class SessionSummaryResponse(CamelModel):
    """{ summary, ttsText, errorTypeCounts, weakSkills[] } — 읽기 전용(상태 갱신 없음).
    errorTypeCounts 는 항상 객체다(오류가 없으면 {}). Java Map<String, Long> 이 null 이 되지 않게."""

    summary: str
    tts_text: str
    error_type_counts: dict[str, int] = Field(default_factory=dict)
    weak_skills: list[WeakSkillSchema]


def _to_states(items: list[SkillStateSchema]) -> list[SkillState]:
    out: list[SkillState] = []
    for s in items:
        errs = s.error_counts.model_dump() if s.error_counts else {}
        out.append(
            SkillState(
                learning_skill_id=s.learning_skill_id,
                n_attempts=s.n_attempts,
                n_correct=s.n_correct,
                last_practiced_at=s.last_practiced_at.replace(tzinfo=None)
                if s.last_practiced_at
                else None,
                mastery_prob=s.mastery_prob,
                error_counts={k: v for k, v in errs.items() if v},
            )
        )
    return out


def _state_out(s: SkillState) -> SkillStateOutSchema:
    return SkillStateOutSchema(
        learning_skill_id=s.learning_skill_id,
        n_attempts=s.n_attempts,
        n_correct=s.n_correct,
        last_practiced_at=(
            s.last_practiced_at.replace(microsecond=0).isoformat() if s.last_practiced_at else ""
        ),
        mastery_prob=s.mastery_prob if s.mastery_prob is not None else 0.0,
        error_counts=dict(s.error_counts),
    )


# ── DI ───────────────────────────────────────────────────────────────────────
def get_problem_service() -> ProblemAnalyzeService:
    return ProblemAnalyzeService(catalog=get_skill_catalog())


def get_review_service() -> ReviewAnalyzeService:
    return ReviewAnalyzeService(catalog=get_skill_catalog(), estimator=default_estimator())


def get_session_service() -> SessionService:
    return SessionService(catalog=get_skill_catalog(), estimator=BKTEstimator())


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
    return ReviewAnalyzeResponse(learning_skill_ids=result.learning_skill_ids)


@router.post(
    "/attempt-analyze", response_model=AttemptAnalyzeResponse, response_model_by_alias=True
)
def attempt_analyze(
    request: AttemptAnalyzeRequest,
    service: SessionService = Depends(get_session_service),
) -> AttemptAnalyzeResponse:
    """문항 하나 제출 직후: 채점 → 피드백 문장 → 요소별 정오 → 학습자 상태 갱신(변경분)."""
    a = request.attempt
    if any(not 0 <= v <= 63 for v in a.input_patterns + a.expected_patterns):
        raise HTTPException(status_code=400, detail="셀 값은 0~63 정수여야 해요")
    try:
        result = service.analyze_attempt(
            Attempt(a.target_text.strip(), a.expected_patterns, a.input_patterns),
            _to_states(request.skill_states),
        )
    except ValueError as e:
        raise HTTPException(
            status_code=422, detail=f"순수 한글 어절만 채점할 수 있어요: {e}"
        ) from e
    return AttemptAnalyzeResponse(
        feedback=FeedbackSchema(tts=result.feedback_tts, detail=result.feedback_detail),
        skill_results=[
            SkillResultSchema(
                learning_skill_id=r.learning_skill_id,
                correct=r.correct,
                error_counts=dict(r.error_counts),
            )
            for r in result.skill_results
        ],
        updated_skill_states=[_state_out(s) for s in result.updated_skill_states],
    )


@router.post(
    "/session-summary", response_model=SessionSummaryResponse, response_model_by_alias=True
)
def session_summary(
    request: SessionSummaryRequest,
    service: SessionService = Depends(get_session_service),
) -> SessionSummaryResponse:
    """12문항을 다 푼 뒤 결과 화면용 요약. 상태를 바꾸지 않는다(여러 번 호출 가능)."""
    for a in request.attempts:
        if any(not 0 <= v <= 63 for v in a.input_patterns + a.expected_patterns):
            raise HTTPException(status_code=400, detail="셀 값은 0~63 정수여야 해요")
    try:
        result = service.summarize_session(
            [
                Attempt(a.target_text.strip(), a.expected_patterns, a.input_patterns)
                for a in request.attempts
            ],
            _to_states(request.skill_states),
        )
    except ValueError as e:
        raise HTTPException(
            status_code=422, detail=f"순수 한글 어절만 채점할 수 있어요: {e}"
        ) from e
    return SessionSummaryResponse(
        summary=result.summary,
        tts_text=result.tts_text,
        error_type_counts=dict(result.error_type_counts),
        weak_skills=[
            WeakSkillSchema(
                learning_skill_id=w.learning_skill_id,
                mastery_prob=w.mastery_prob,
                label=w.label,
                dominant_error_type=w.dominant_error_type,
                explanation=w.explanation,
            )
            for w in result.weak_skills
        ],
    )
