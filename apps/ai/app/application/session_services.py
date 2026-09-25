"""세션 흐름 유스케이스 (2026-09-25 계약 확정)

  analyze_attempt(attempt, skill_states)  → feedback · skillResults · updatedSkillStates(변경분)   POST /attempt-analyze
  summarize_session(attempts, skill_states) → summary · ttsText · weakSkills (읽기 전용)          POST /session-summary

실력 갱신은 attempt-analyze 에서만 일어난다. session-summary 는 상태를 바꾸지 않으므로 여러 번 불러도 안전하다.
학습자 상태(skillStates)는 Spring 이 저장·전달하고, 이 서비스는 상태를 갖지 않는다.
"""

from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from app.domain.skills.models import SkillCatalog
from app.infrastructure.braille_core.estimate import BKTEstimator, label_of
from app.infrastructure.braille_core.grader import grade, josa, speak_kc

MAX_WEAK = 5  # weakSkills 최대 개수
EXPLAIN = {
    "mirror": "좌우를 뒤집어 쓰는 실수가 반복됐어요",
    "vflip": "위아래를 뒤집어 쓰는 실수가 있었어요",
    "rot180": "반 바퀴 돌려 쓰는 실수가 있었어요",
    "shift_down": "한 줄 아래로 내려 쓰는 실수가 있었어요",
    "shift_up": "한 줄 위로 올려 쓰는 실수가 있었어요",
    "dot_missing": "점 누락이 반복됐어요",
    "dot_extra": "점을 더 찍는 실수가 있었어요",
    "dot_mixed": "점 위치가 자주 달랐어요",
    "jamo_confusion": "다른 글자와 헷갈리는 실수가 있었어요",
    "cell_omitted": "칸을 빠뜨리는 실수가 있었어요",
    "cell_inserted": "칸을 더 넣는 실수가 있었어요",
    "transposition": "칸 순서가 바뀌는 실수가 있었어요",
    "abbr_expanded": "약자를 풀어 쓰는 실수가 반복됐어요",
    "abbr_overused": "약자를 쓰지 말아야 할 자리에 쓰는 실수가 있었어요",
    "marker_missing": "된소리표나 구분표를 빠뜨리는 실수가 있었어요",
    "silent_onset_written": "첫소리 이응을 적는 실수가 반복됐어요",
}


@dataclass
class SkillState:
    """Spring 의 SkillState 와 1:1 (내부 표현)."""

    learning_skill_id: int
    n_attempts: int = 0
    n_correct: int = 0
    last_practiced_at: datetime | None = None
    mastery_prob: float | None = None
    error_counts: dict[str, int] = field(default_factory=dict)

    def to_estimate(self, kc: str) -> dict[str, Any]:
        return {
            "kc": kc,
            "n_attempts": self.n_attempts,
            "n_correct": self.n_correct,
            "last_practice": self.last_practiced_at.isoformat() if self.last_practiced_at else None,
            "mastery_prob": self.mastery_prob,
            "n_errors_by_type": dict(self.error_counts),
        }


@dataclass(frozen=True)
class Attempt:
    target_text: str
    expected_patterns: list[int]
    input_patterns: list[int]


@dataclass(frozen=True)
class SkillResult:
    learning_skill_id: int
    correct: bool
    error_counts: dict[str, int]


@dataclass(frozen=True)
class AttemptAnalysis:
    correct: bool
    feedback_tts: str
    feedback_detail: str
    skill_results: list[SkillResult]
    updated_skill_states: list[SkillState]
    warnings: list[str]


@dataclass(frozen=True)
class WeakSkill:
    learning_skill_id: int
    mastery_prob: float
    label: str
    dominant_error_type: str | None
    explanation: str


@dataclass(frozen=True)
class SessionSummary:
    n_items: int
    n_correct: int
    summary: str
    tts_text: str
    weak_skills: list[WeakSkill]
    error_type_counts: dict[str, int]  # 이번 세션 오류 유형별 횟수. 오류가 없으면 {} (null 아님)


@dataclass
class SessionService:
    catalog: SkillCatalog
    estimator: BKTEstimator

    # ── 공통 ────────────────────────────────────────────────────────────────
    def _grade(self, attempt: Attempt, warnings: list[str]) -> dict[str, Any]:
        result = grade(attempt.target_text, attempt.input_patterns)
        if attempt.expected_patterns and result["expected_cells"] != attempt.expected_patterns:
            warnings.append(
                f"'{attempt.target_text}': expectedPatterns {attempt.expected_patterns} 가 규칙 변환값 "
                f"{result['expected_cells']} 와 다릅니다 (규칙 변환값으로 채점)"
            )
        return result

    def _skill_results(self, graded: dict[str, Any]) -> list[SkillResult]:
        """문항의 요소별 정오. 불변식: correct == (errorCounts 가 비어 있음).

        칸 정오(정렬 결과)와 오류 귀속이 어긋나는 두 경우를 여기서 맞춘다.
          · 규칙 오류로 귀속된 칸 — 예: '마음'을 약자 '마'로 써서 ㅏ 칸이 빠짐 → 오류는 '규칙:약자미사용'.
            빠진 ㅏ 칸은 규칙 오류의 결과일 뿐 ㅏ 를 모른 게 아니므로 ㅏ 요소는 틀림으로 세지 않는다.
          · 순서 바뀜(transposition) — 오류는 앞 칸에만 붙으므로 뒤 칸 요소에도 같은 유형을 붙인다.
        그래도 틀렸는데 설명할 오류가 없는 요소가 남으면 포괄 유형 dot_mixed 로 채운다.
        """
        cell_kcs: list[str] = list(graded["kc"])
        counts: dict[str, Counter[str]] = {}
        excused: set[int] = set()
        for e in graded["errors"]:
            etype: str = e["type"]
            kc: str | None = e.get("kc")
            cell = e.get("cell")
            targets: set[str] = {kc} if kc else set()
            if isinstance(cell, int) and 0 <= cell < len(cell_kcs):
                if kc and kc.startswith("규칙:") and cell_kcs[cell] != kc:
                    excused.add(cell)
                if etype == "transposition" and cell + 1 < len(cell_kcs):
                    targets.add(cell_kcs[cell + 1])
            for t in targets:
                counts.setdefault(t, Counter())[etype] += 1

        order: list[str] = []
        observed_ok: dict[str, bool] = {}
        for o in graded["kc_outcomes"]:
            okc: str = o["kc"]
            if okc not in observed_ok:
                order.append(okc)
                observed_ok[okc] = True
            ok = bool(o["correct"]) or (isinstance(o.get("cell"), int) and o["cell"] in excused)
            observed_ok[okc] = observed_ok[okc] and ok
        known = self._known_kcs()
        for ekc in counts:  # 오류는 있는데 결과 목록에 없던 요소(드묾)
            if ekc not in observed_ok and ekc in known:
                order.append(ekc)
                observed_ok[ekc] = False

        results: list[SkillResult] = []
        for rkc in order:
            c = counts.get(rkc, Counter())
            if not observed_ok[rkc] and not c:
                c = Counter({"dot_mixed": 1})  # 틀렸지만 설명할 유형이 없을 때의 포괄 유형
            results.append(SkillResult(self.catalog.by_kc(rkc).id, not c, dict(c)))
        return results

    def _known_kcs(self) -> set[str]:
        return {s.kc for s in self.catalog.all()}

    # ── ① 문항 하나 ────────────────────────────────────────────────────────
    def analyze_attempt(
        self, attempt: Attempt, skill_states: list[SkillState], now: datetime | None = None
    ) -> AttemptAnalysis:
        now = now or datetime.now(UTC).replace(tzinfo=None)
        warnings: list[str] = []
        graded = self._grade(attempt, warnings)
        results = self._skill_results(graded)

        states = {s.learning_skill_id: s for s in skill_states}
        updated: list[SkillState] = []
        for r in results:
            kc = self.catalog.by_id(r.learning_skill_id)
            assert kc is not None
            s = states.get(r.learning_skill_id) or SkillState(r.learning_skill_id)
            est_state = s.to_estimate(kc.kc)
            p = s.mastery_prob
            if p is not None and s.last_practiced_at is not None:
                p = self.estimator._decay(p, est_state, now)  # 마지막 연습 후 망각을 먼저 반영
            p = self.estimator.update(p, r.correct)  # 이번 관측 반영
            counts = dict(s.error_counts)
            for t, n in r.error_counts.items():
                counts[t] = counts.get(t, 0) + n
            updated.append(
                SkillState(
                    learning_skill_id=r.learning_skill_id,
                    n_attempts=s.n_attempts + 1,
                    n_correct=s.n_correct + int(r.correct),
                    last_practiced_at=now,
                    mastery_prob=round(p, 4),
                    error_counts=counts,
                )
            )
        fb = graded["feedback"]
        detail = self._detail(graded, results)
        return AttemptAnalysis(
            correct=bool(graded["correct"]),
            feedback_tts=fb["tts"],
            feedback_detail=detail,
            skill_results=results,
            updated_skill_states=updated,
            warnings=warnings,
        )

    def _detail(self, graded: dict[str, Any], results: list[SkillResult]) -> str:
        wrong = [r for r in results if not r.correct]
        if not wrong:
            return "정답이에요. 다음 문제로 넘어갈게요."
        skill = self.catalog.by_id(wrong[0].learning_skill_id)
        if skill is None:
            return graded["feedback"]["tts"]
        name = speak_kc(skill.kc)
        if skill.kc == "규칙:초성ㅇ생략":
            return "첫소리 이응은 점자로 적지 않습니다. 모음부터 바로 씁니다."
        if skill.kc == "규칙:약자미사용":
            return "나·다·마·바·자·카·타·파·하 뒤에 모음이 이어지면 약자를 쓰지 않고 자음과 아를 따로 씁니다."
        exp = graded["expected_cells"]
        idx = next(
            (o["cell"] for o in graded["kc_outcomes"] if o["kc"] == skill.kc and "cell" in o), None
        )
        if isinstance(idx, int) and 0 <= idx < len(exp):
            dots = [d + 1 for d in range(6) if exp[idx] >> d & 1]
            return f"{name}{josa(name, ('은', '는'))} {', '.join(map(str, dots))}점입니다. 점을 하나씩 확인하며 다시 눌러 보세요."
        return f"{name}{josa(name, ('을', '를'))} 다시 확인해 보세요."

    # ── ② 세션 요약 (읽기 전용) ───────────────────────────────────────────
    def summarize_session(
        self, attempts: list[Attempt], skill_states: list[SkillState], now: datetime | None = None
    ) -> SessionSummary:
        now = now or datetime.now(UTC).replace(tzinfo=None)
        warnings: list[str] = []
        graded = [self._grade(a, warnings) for a in attempts]
        n_correct = sum(1 for g in graded if g["correct"])
        touched = {o["kc"] for g in graded for o in g["kc_outcomes"]}

        states = {s.learning_skill_id: s for s in skill_states}
        weak: list[WeakSkill] = []
        kc_of: dict[int, str] = {}
        for kc in touched:
            skill = self.catalog.by_kc(kc)
            s = states.get(skill.id)
            if s is None or s.n_attempts < 2:
                continue  # 기록 1건뿐인 요소는 판정하지 않는다
            p = self.estimator.mastery([s.to_estimate(kc)], now).get(kc, self.estimator.prior())
            label = label_of(p)
            if label == "익숙해요":
                continue
            dom = max(s.error_counts.items(), key=lambda kv: kv[1])[0] if s.error_counts else None
            expl = EXPLAIN.get(dom or "", "조금 더 연습이 필요해요")
            weak.append(WeakSkill(skill.id, round(p, 4), label, dom, f"{speak_kc(kc)}에서 {expl}."))
            kc_of[skill.id] = kc
        weak.sort(key=lambda w: w.mastery_prob)
        weak = weak[:MAX_WEAK]

        names = [speak_kc(kc_of[w.learning_skill_id]) for w in weak if w.label == "더 연습해요"][:2]
        n = len(attempts)
        summary = f"{n}문제 중 {n_correct}문제를 맞혔어요."
        tts = f"오늘 {n}문제 중 {n_correct}문제를 맞혔어요."
        if names:
            joined = ", ".join(names)
            summary += f" {joined}{josa(names[-1], ('은', '는'))} 조금 더 연습하면 좋겠어요."
            tts += f" {joined}{josa(names[-1], ('을', '를'))} 다시 연습해 보세요."
        else:
            tts += " 아주 잘했어요."
        return SessionSummary(n, n_correct, summary, tts, weak, error_type_counts(graded))


def error_type_counts(graded: list[dict[str, Any]]) -> dict[str, int]:
    """세션 오류 유형별 횟수(요소 합산). 오류가 없으면 빈 dict — 응답에서 null 이 되지 않게 한다."""
    return dict(Counter(e["type"] for g in graded for e in g["errors"]))
