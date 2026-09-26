"""세션 흐름 유스케이스 (2026-09-25 계약 확정)

  analyze_attempt(attempt, skill_states)  → feedback · skillResults · updatedSkillStates(변경분)   POST /attempt-analyze
  summarize_session(attempts, skill_states) → summary · ttsText · weakSkills (읽기 전용)          POST /session-summary

실력 갱신은 attempt-analyze 에서만 일어난다. session-summary 는 상태를 바꾸지 않으므로 여러 번 불러도 안전하다.
학습자 상태(skillStates)는 Spring 이 저장·전달하고, 이 서비스는 상태를 갖지 않는다.
"""

import json
import logging
from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.domain.skills.models import SkillCatalog
from app.infrastructure.braille_core.braille_utils import dots_to_int
from app.infrastructure.braille_core.drill_items import build_drill_item
from app.infrastructure.braille_core.estimate import BKTEstimator, label_of
from app.infrastructure.braille_core.grader import grade, josa, speak_kc
from app.infrastructure.braille_core.shadow_model import (
    JsonTreeModel,
    ShadowPredictor,
    features_for,
)

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


RECENT_RESULTS_LEN = 5  # recentResults 에 남기는 최근 관측 수
SHADOW_MODEL_PATH = (
    Path(__file__).resolve().parents[1] / "infrastructure" / "data" / "lgbm_shadow_v1.json"
)
shadow_log = logging.getLogger("itda.shadow")
_shadow_cache: dict[str, ShadowPredictor | None] = {}


def load_shadow_predictor() -> ShadowPredictor | None:
    """그림자 모델. 파일이 없거나 깨져도 서비스는 그대로 돈다 (None)."""
    if "p" not in _shadow_cache:
        try:
            _shadow_cache["p"] = ShadowPredictor(JsonTreeModel.load(SHADOW_MODEL_PATH))
        except Exception as e:  # noqa: BLE001 — 그림자 모델 문제는 응답에 영향을 주면 안 된다
            shadow_log.warning("shadow model unavailable: %s", e)
            _shadow_cache["p"] = None
    return _shadow_cache["p"]


@dataclass
class SkillState:
    """Spring 의 SkillState 와 1:1 (내부 표현)."""

    learning_skill_id: int
    n_attempts: int = 0
    n_correct: int = 0
    last_practiced_at: datetime | None = None
    mastery_prob: float | None = None
    error_counts: dict[str, int] = field(default_factory=dict)
    recent_results: str = ""  # 최근 5회 정오, 오래된 것부터 ('0'=틀림 '1'=맞음). 학습 모델 특징용

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
    cell_skills: list["CellSkill"] = field(default_factory=list)
    feedback_steps: list["FeedbackStep"] = field(default_factory=list)


@dataclass(frozen=True)
class FeedbackStep:
    """오답의 '틀린 곳' 하나 — 화면 한 장. 정답 칸이 없으면(없어야 할 칸을 넣음) expected_* 가 None 이고 label='빈칸'."""

    expected_cell_index: int | None  # 1부터. 정답 칸이 없으면 None
    expected_pattern: int | None
    input_pattern: int | None  # 학습자가 그 자리에 쓴 칸. 빠뜨렸으면 None
    missing_dots: list[int]
    extra_dots: list[int]
    label: str  # 정답 칸의 요소 이름("받침 이응") 또는 "빈칸"
    error_type: str
    tts: str  # 이 틀린 곳을 설명하는 한 문장 (AI 채점기 문장)


def _is_dots(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(d, int) for d in value)


def label_cells(
    kcs: list[str], cell_syllables: list[int | None] | None, catalog: SkillCatalog
) -> list["CellSkill"]:
    """정답 칸마다 요소 라벨. 한 요소가 이어진 여러 칸을 차지하면(ㅙ, ㄳ, 약어, 받침 ㄲ) '(1/2)'를 붙인다.
    같은 요소라도 다른 음절이면('가가') 따로 센다."""
    syls: list[int | None] = list(cell_syllables or [None] * len(kcs))
    groups: list[list[int]] = []
    for i, kc in enumerate(kcs):
        if groups and kcs[groups[-1][-1]] == kc and syls[groups[-1][-1]] == syls[i]:
            groups[-1].append(i)
        else:
            groups.append([i])
    out: list[CellSkill] = []
    for g in groups:
        skill = catalog.by_kc(kcs[g[0]])
        base = skill_label(skill.display_name)
        for k, i in enumerate(g, 1):
            label = f"{base} ({k}/{len(g)})" if len(g) > 1 else base
            out.append(CellSkill(i + 1, skill.id, label))
    return out


@dataclass(frozen=True)
class CellSkill:
    """정답 칸 하나가 어떤 학습 요소인지. 화면의 '정답 · 약자 바' 캡션용."""

    cell_index: int  # 1부터 (백엔드 attempt_cells.cell_index 와 같음)
    learning_skill_id: int
    label: str  # "약자 바", "첫소리 ㅂ", "모음 ㅙ (1/2)"


def skill_label(display_name: str) -> str:
    """화면용 짧은 이름. 카탈로그 이름('초성 ㅂ', '구분표(모음+예)')을 학습자가 읽기 쉽게 다듬는다."""
    if display_name.startswith("초성 "):
        return "첫소리 " + display_name[len("초성 ") :]
    if display_name.startswith("구분표"):
        return "구분표"
    return display_name


@dataclass(frozen=True)
class SkillMastery:
    """요소 하나의 숙련 상태. 세션 밖에서 읽는다."""

    learning_skill_id: int
    mastery_prob: float
    label: str


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
        item = build_drill_item(
            attempt.target_text, attempt.expected_patterns
        )  # 'ㄱ'·'ㅙ'·'초성 ㄱ' 같은 낱자·드릴
        result = grade(attempt.target_text, attempt.input_patterns, item=item)
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

    # ── 숙련 상태 읽기 (점역을 거치지 않는다) ───────────────────────────────
    def summarize_skills(
        self, skill_states: list[SkillState], now: datetime | None = None
    ) -> list[SkillMastery]:
        """요소별 숙련도와 라벨. 약한 것부터 정렬해 돌려준다.

        "내 실력" 화면과 홈의 복습 카드가 세션과 무관하게 "무엇이 약한지"를 보여주려면
        이 값이 필요하다. 지금까지 숙련 라벨은 세션 완료 응답(weakSkills)에만 실려서,
        두 화면은 고정값을 내 실력처럼 보여주고 있었다.

        라벨 기준(label_of)은 여기에만 둔다 — Spring 이 문턱값을 베끼면 두 곳이 갈린다.

        정렬도 여기서 한다. 앱이 하면 두 화면이 서로 다른 순서를 쓸 수 있다.
        """
        now = now or datetime.now(UTC).replace(tzinfo=None)
        out: list[SkillMastery] = []
        for state in skill_states:
            key = str(state.learning_skill_id)
            p = self.estimator.mastery([state.to_estimate(key)], now).get(
                key, self.estimator.prior()
            )
            out.append(SkillMastery(state.learning_skill_id, round(p, 4), label_of(p)))
        out.sort(key=lambda m: m.mastery_prob)
        return out

    # ── 숙련도 갱신 (점역을 거치지 않는다) ──────────────────────────────────
    def apply_results(
        self,
        results: list[SkillResult],
        skill_states: list[SkillState],
        now: datetime | None = None,
    ) -> list[SkillState]:
        """요소별 정오만으로 학습자 상태를 갱신한다.

        analyze_attempt 가 채점을 마친 뒤 쓰던 블록을 그대로 떼어냈다. 점역 결과(graded)를
        보지 않으므로 글자가 없어도 쓸 수 있다 — 자음·모음 단계 문항은 'ㄱ'·'ㅙ' 같은
        낱자라 점역기가 분해하지 못해 Spring 이 직접 채점하는데, 숙련도는 여기(BKT)에만
        있어 갱신할 방법이 없었다. 그 탓에 자음·모음은 아무리 틀려도 약한 요소로 뽑히지
        않아 복습에 영영 나오지 않았다.

        catalog 를 조회하지 않는다. 옛 코드가 kc 를 꺼낸 것은 to_estimate 의 인자 때문인데
        _decay 는 그 값을 쓰지 않는다(마지막 연습 시각과 정답 횟수만 본다).
        """
        now = now or datetime.now(UTC).replace(tzinfo=None)
        states = {s.learning_skill_id: s for s in skill_states}
        updated: list[SkillState] = []
        for r in results:
            s = states.get(r.learning_skill_id) or SkillState(r.learning_skill_id)
            est_state = s.to_estimate("")
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
                    recent_results=(s.recent_results + ("1" if r.correct else "0"))[
                        -RECENT_RESULTS_LEN:
                    ],
                )
            )
        return updated

    # ── 그림자 모델: 응답에는 쓰지 않고 로그에만 남긴다 ─────────────────────
    def _log_shadow(
        self,
        attempt: Attempt,
        graded: dict[str, Any],
        results: list[SkillResult],
        skill_states: list[SkillState],
        now: datetime,
    ) -> None:
        """이 문항을 풀기 전 상태로 '확률 갱신식(BKT)'과 '학습 모델(LightGBM)'이 각각 예측한 P(맞힘)과
        실제 결과를 한 줄 JSON 으로 남긴다. 파일럿 로그를 모아 실제 학습자에게서 누가 더 맞는지 비교한다."""
        predictor = load_shadow_predictor()
        if predictor is None or not results:
            return
        try:
            by_id = {s.learning_skill_id: s for s in skill_states}
            st_dicts = [
                {
                    "learning_skill_id": s.learning_skill_id,
                    "n_attempts": s.n_attempts,
                    "n_correct": s.n_correct,
                    "last_practiced_at": s.last_practiced_at,
                    "mastery_prob": s.mastery_prob,
                    "error_counts": s.error_counts,
                    "recent_results": s.recent_results,
                }
                for s in skill_states
            ]
            item_ids = [r.learning_skill_id for r in results]
            is_drill = build_drill_item(attempt.target_text, attempt.expected_patterns) is not None
            rows: list[dict[str, Any]] = []
            for r in results:
                skill = self.catalog.by_id(r.learning_skill_id)
                if skill is None:
                    continue
                st = by_id.get(r.learning_skill_id)
                p_bkt = self.estimator.prior()
                if st is not None and st.mastery_prob is not None:
                    p_bkt = st.mastery_prob
                    if st.last_practiced_at is not None:
                        p_bkt = self.estimator._decay(p_bkt, st.to_estimate(skill.kc), now)
                x = features_for(
                    r.learning_skill_id,
                    skill.level,
                    next(
                        (d for d in st_dicts if d["learning_skill_id"] == r.learning_skill_id), None
                    ),
                    st_dicts,
                    item_ids,
                    len(graded["expected_cells"]),
                    is_drill,
                    now,
                )
                rows.append(
                    {
                        "skill": r.learning_skill_id,
                        "p_bkt": round(p_bkt, 4),
                        "p_lgbm": round(predictor.predict(x), 4),
                        "correct": r.correct,
                    }
                )
            shadow_log.info(
                json.dumps(
                    {
                        "event": "shadow_prediction",
                        "model": predictor.version,
                        "ts": now.isoformat(),
                        "text": attempt.target_text,
                        "n_cells": len(graded["expected_cells"]),
                        "skills": rows,
                    },
                    ensure_ascii=False,
                )
            )
        except Exception as e:  # noqa: BLE001
            shadow_log.warning("shadow prediction skipped: %s", e)

    # ── ① 문항 하나 ────────────────────────────────────────────────────────
    def analyze_attempt(
        self, attempt: Attempt, skill_states: list[SkillState], now: datetime | None = None
    ) -> AttemptAnalysis:
        now = now or datetime.now(UTC).replace(tzinfo=None)
        warnings: list[str] = []
        graded = self._grade(attempt, warnings)
        results = self._skill_results(graded)
        self._log_shadow(attempt, graded, results, skill_states, now)

        updated = self.apply_results(results, skill_states, now)
        fb = graded["feedback"]
        detail = self._detail(graded, results)
        return AttemptAnalysis(
            correct=bool(graded["correct"]),
            feedback_tts=fb["tts"],
            feedback_detail=detail,
            skill_results=results,
            updated_skill_states=updated,
            warnings=warnings,
            cell_skills=(cells := self._cell_skills(graded)),
            feedback_steps=self._feedback_steps(graded, cells),
        )

    def _cell_skills(self, graded: dict[str, Any]) -> list[CellSkill]:
        return label_cells(list(graded["kc"]), graded.get("cell_syllables"), self.catalog)

    def _feedback_steps(
        self, graded: dict[str, Any], cell_skills: list[CellSkill]
    ) -> list["FeedbackStep"]:
        """오답 화면이 하나씩 넘겨 보여줄 '틀린 곳'. 오류 하나 = 단계 하나, 앞 칸부터.

        백엔드의 칸 비교는 같은 번호끼리 맞대므로 칸을 하나 더 넣으면 뒤가 전부 밀려 '모두 N칸이 틀렸어요'가 된다.
        여기서는 정렬(칸 맞추기) 뒤의 오류만 단계로 만든다 — '음식'을 ㅇ까지 6칸으로 쓰면 단계는 하나,
        '첫소리 이응은 점자로 적지 않아요'이고 정답 칸은 빈칸이다."""
        if graded["correct"]:
            return []
        exp: list[list[int]] = graded["expected_dots"]
        labels = {c.cell_index - 1: c.label for c in cell_skills}
        steps: list[FeedbackStep] = []
        for e in sorted(
            graded["errors"], key=lambda x: (x.get("cell") if x.get("cell") is not None else 1_000)
        ):
            etype: str = e["type"]
            i = e.get("cell")
            act = e.get("actual")
            if etype in (
                "silent_onset_written",
                "cell_inserted",
            ):  # 없어야 할 칸을 넣음 → 정답은 빈칸
                exp_dots, act_dots, exp_idx = None, act if _is_dots(act) else None, None
            else:
                exp_dots = exp[i] if isinstance(i, int) and 0 <= i < len(exp) else None
                if etype in (
                    "cell_omitted",
                    "abbr_overused",
                ):  # 있어야 할 칸을 빠뜨림 → 내 입력은 없음
                    act_dots = None
                elif (
                    isinstance(act, list) and act and isinstance(act[0], list)
                ):  # 순서 바뀜·약자 풀어 씀: 첫 칸
                    act_dots = act[0]
                else:
                    act_dots = act if _is_dots(act) else None
                exp_idx = i + 1 if exp_dots is not None and isinstance(i, int) else None
            e_set, a_set = set(exp_dots or []), set(act_dots or [])
            steps.append(
                FeedbackStep(
                    expected_cell_index=exp_idx,
                    expected_pattern=dots_to_int(exp_dots) if exp_dots is not None else None,
                    input_pattern=dots_to_int(act_dots) if act_dots is not None else None,
                    missing_dots=sorted(e_set - a_set),
                    extra_dots=sorted(a_set - e_set),
                    label=labels.get(i, "빈칸")
                    if exp_idx is not None and isinstance(i, int)
                    else "빈칸",
                    error_type=etype,
                    tts=str(e.get("sentence") or ""),
                )
            )
        return steps

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
