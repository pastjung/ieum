"""shadow_model.py — 그림자 모델(LightGBM)을 의존성 없이 돌린다.

목적: 확률 갱신식(BKT)이 실제 응답을 만드는 동안, 학습 모델도 같은 요청으로 예측을 계산해 로그에만 남긴다.
      파일럿 로그를 나중에 모아 "실제 학습자에게서 누가 더 잘 맞히는가"를 비교한 뒤 교체한다.

구성
  features_for(...)   요청의 skillStates + 문항 맥락에서 요소별 특징 벡터를 만든다 — 학습 때와 서빙 때 같은 함수
  JsonTreeModel        LightGBM 의 dump_model() JSON 을 읽어 순수 파이썬으로 예측 (런타임에 lightgbm 불필요)
  ShadowPredictor      둘을 묶어 요소별 P(맞힘)을 낸다

특징(총 30개)은 전부 백엔드가 지금 보내는 값에서 나온다. 없는 값은 0 으로 두고 그 자리를 "없음" 플래그로 남긴다.
"""

import json
import math
from datetime import datetime
from pathlib import Path
from typing import Any

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
FEATURE_NAMES: tuple[str, ...] = (
    "n_att",
    "n_cor",
    "acc",
    "recent_cor",
    "recent_n",
    "recent_last",
    "days_since",
    "log_days",
    "has_history",
    "mastery_prob",
    "skill_id",
    "skill_stage",
    "n_cells",
    "is_drill",
    "n_skills_in_item",
    "item_lag_min",
    "learner_acc",
    "learner_n",
    *(f"err_{t}" for t in ERROR_TYPES),
)
NO_HISTORY_DAYS = 30.0
STAGE_INDEX = {
    "CONSONANT": 0,
    "VOWEL": 1,
    "CONTRACTION": 2,
    "CONTRACTION_WORD": 3,
    "WORD": 4,
    "SENTENCE": 5,
}


def _days(last: datetime | None, now: datetime) -> float:
    if last is None:
        return NO_HISTORY_DAYS
    return max(0.0, (now - last).total_seconds() / 86400)


def features_for(
    skill_id: int,
    stage: str,
    state: dict[str, Any] | None,
    states: list[dict[str, Any]],
    item_skill_ids: list[int],
    n_cells: int,
    is_drill: bool,
    now: datetime,
) -> list[float]:
    """요소 하나의 특징 벡터.

    state / states 의 원소: {"learning_skill_id", "n_attempts", "n_correct", "last_practiced_at"(datetime|None),
                             "mastery_prob"(float|None), "error_counts"({type: n}), "recent_results"(str)}
    """
    s = state or {}
    n, c = int(s.get("n_attempts", 0)), int(s.get("n_correct", 0))
    recent = str(s.get("recent_results") or "")
    days = _days(s.get("last_practiced_at"), now)
    by_id = {int(x["learning_skill_id"]): x for x in states}
    tot_n = sum(int(x.get("n_attempts", 0)) for x in states)
    tot_c = sum(int(x.get("n_correct", 0)) for x in states)
    item_lag = min(
        (_days(by_id[i].get("last_practiced_at"), now) for i in item_skill_ids if i in by_id),
        default=NO_HISTORY_DAYS,
    )
    err = s.get("error_counts") or {}
    mp = s.get("mastery_prob")
    return [
        n,
        c,
        (c + 1) / (n + 2),
        recent.count("1"),
        len(recent),
        1.0 if recent.endswith("1") else 0.0,
        days,
        math.log1p(days),
        1.0 if n > 0 else 0.0,
        float(mp) if mp is not None else 0.2,
        skill_id,
        STAGE_INDEX.get(stage, 4),
        n_cells,
        1.0 if is_drill else 0.0,
        len(item_skill_ids),
        item_lag,
        (tot_c + 1) / (tot_n + 2),
        tot_n,
        *(float(err.get(t, 0)) for t in ERROR_TYPES),
    ]


class JsonTreeModel:
    """LightGBM Booster.dump_model() 결과를 읽어 예측한다 (이진 분류, 시그모이드)."""

    def __init__(self, dump: dict[str, Any]) -> None:
        self.trees = [t["tree_structure"] for t in dump["tree_info"]]
        names = dump.get("feature_names") or list(FEATURE_NAMES)
        if list(names) != list(FEATURE_NAMES):
            raise ValueError(
                "모델의 특징 순서가 FEATURE_NAMES 와 다릅니다 — 같은 shadow_model.features_for 로 학습해야 합니다"
            )
        self.version = str(dump.get("version_tag", "lgbm-shadow"))

    @classmethod
    def load(cls, path: Path) -> "JsonTreeModel":
        return cls(json.loads(path.read_text(encoding="utf-8")))

    @staticmethod
    def _leaf(node: dict[str, Any], x: list[float]) -> float:
        while "leaf_value" not in node:
            v = x[node["split_feature"]]
            go_left = (
                v <= node["threshold"] if not math.isnan(v) else node.get("default_left", True)
            )
            node = node["left_child"] if go_left else node["right_child"]
        return float(node["leaf_value"])

    def raw(self, x: list[float]) -> float:
        return sum(self._leaf(t, x) for t in self.trees)

    def predict(self, x: list[float]) -> float:
        z = self.raw(x)
        return 1.0 / (1.0 + math.exp(-z))


class ShadowPredictor:
    def __init__(self, model: JsonTreeModel) -> None:
        self.model = model
        self.version = model.version

    def predict(self, x: list[float]) -> float:
        return self.model.predict(x)
