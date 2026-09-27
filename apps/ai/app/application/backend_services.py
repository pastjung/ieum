"""백엔드 연동 유스케이스 두 개 (FastApiClient.java 가 부르는 것).

analyze_problem(target_text) → level·cells·skillSets       (ProblemService.createProblem)
analyze_review(skill_states) → level·learningSkillIds      (ProblemService.reviewWithNewSession)
"""

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from app.domain.skills.models import SkillCatalog, SkillSet
from app.infrastructure.braille_core import curriculum, kbraille
from app.infrastructure.braille_core.estimate import Estimator

TOP_WEAK = 3  # 복습 세션에 넣는 약한 요소 수
WEAK_THRESHOLD = 0.70


class UnsupportedTextError(ValueError):
    """순수 한글 어절이 아니어서 변환할 수 없을 때 (숫자·문장부호·로마자는 MVP 미지원)."""


@dataclass(frozen=True)
class ProblemAnalysis:
    level: str
    cells: list[int]
    skill_sets: list[SkillSet]
    skill_ids: list[int]  # 참고용 (백엔드 DTO 에는 없음)


@dataclass(frozen=True)
class ReviewAnalysis:
    learning_skill_ids: list[int]
    diagnostics: dict[str, Any] = field(default_factory=dict)


@dataclass
class ProblemAnalyzeService:
    catalog: SkillCatalog

    def analyze(self, target_text: str) -> ProblemAnalysis:
        text = target_text.strip()
        if not text:
            raise UnsupportedTextError("변환할 텍스트가 비어 있습니다.")
        try:
            result = kbraille.analyze(text)
        except ValueError as e:
            raise UnsupportedTextError(f"순수 한글 어절만 변환할 수 있습니다: {e}") from e
        if not result.cells:
            raise UnsupportedTextError("변환 결과가 비어 있습니다.")
        stage = curriculum.stage_of_item(result.kc_all, len(text))
        skills = [self.catalog.by_kc(kc) for kc in result.kc_all]
        return ProblemAnalysis(
            level=curriculum.STAGE_TO_LEVEL[stage],
            cells=[c.value for c in result.cells],
            skill_sets=[SkillSet(s.skill_type, s.metadata) for s in skills],
            skill_ids=[s.id for s in skills],
        )


@dataclass
class ReviewAnalyzeService:
    catalog: SkillCatalog
    estimator: Estimator

    def analyze(
        self,
        skill_states: list[dict[str, Any]],
        now: datetime | None = None,
        level: str | None = None,
    ) -> ReviewAnalysis:
        """skill_states: estimate 규격의 상태 목록 (kc 키 포함). 비어 있으면 첫 복습 → 자음 단계 기본."""
        now = now or datetime.now(UTC).replace(tzinfo=None)
        known = [
            s for s in skill_states if self.catalog.by_id(int(s["learning_skill_id"])) is not None
        ]
        for s in known:
            skill = self.catalog.by_id(int(s["learning_skill_id"]))
            assert skill is not None
            s["kc"] = skill.kc
        mastery = self.estimator.mastery(known, now) if known else {}
        ranked = sorted(mastery.items(), key=lambda kv: kv[1])  # 약한 순
        weak = [kc for kc, p in ranked if p < WEAK_THRESHOLD][:TOP_WEAK] or [
            kc for kc, _ in ranked[:TOP_WEAK]
        ]
        if not weak:
            # 기록이 전혀 없으면: 자음 단계의 기본 요소로 (백엔드가 level 안에서 12개를 뽑을 수 있게)
            weak = ["초성:ㄱ", "초성:ㄴ", "중성:ㅏ"]
        skills = [self.catalog.by_kc(kc) for kc in weak]
        ids = [s.id for s in skills]
        return ReviewAnalysis(
            learning_skill_ids=ids,
            diagnostics={
                "n_states": len(skill_states),
                "n_weak": sum(1 for _, p in ranked if p < WEAK_THRESHOLD),
                "mastery": {kc: round(p, 3) for kc, p in ranked[:TOP_WEAK]},
                "estimator": self.estimator.version,
            },
        )
