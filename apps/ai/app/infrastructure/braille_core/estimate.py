"""estimate.py — 학습 상태 추정 규격 (모델 ↔ 서비스 사이의 콘센트)

서비스가 내리는 결정에서 거꾸로 정한 출력 다섯 가지. 어떤 모델이든(규칙식·BKT·LightGBM·DKT) 이 규격을 구현하면
추천(policy) · 세션 요약(summary) 을 고치지 않고 갈아 끼울 수 있다.

    mastery(states, now)                → {kc: p}          지금 그 개념의 셀을 맞힐 확률      (결과 화면 라벨·복습 선택)
    mastery_at(states, kc, now, dt)     → p                dt일 뒤에 연습하면 맞힐 확률       (복습 시점)
    error_pattern(states)               → {type: share}    틀릴 때 어떤 유형으로 틀리는가     (연습 종류)
    p_correct(states, item_kcs, now)    → p                문항(개념 집합) 정답 확률          (난이도 조절)
    confidence(states)                  → {kc: n}          추정의 근거 기록 수                (적으면 보수적으로)

입력 states 는 백엔드가 저장·전달하는 개념별 요약이다 (모델은 DB 를 보지 않는다):
    {"kc": "종성:ㄱ", "n_attempts": 5, "n_correct": 3, "last_practice": "2026-10-12T09:00:00",
     "n_errors_by_type": {"mirror": 1, "dot_missing": 1}}          # 마지막 항목은 선택
순서열이 필요한 모델(DKT)은 states 대신 attempts 이력을 받는 별도 구현이 되며, 출력 규격은 같다.
"""

import math
from datetime import datetime
from typing import Any

State = dict[str, Any]

LABELS = [
    (0.95, "익숙해요"),
    (0.70, "배우는 중"),
    (0.0, "더 연습해요"),
]  # 결과 화면 세 문구 (경계 0.70 / 0.95)
FLOOR = 0.05


def label_of(p: float) -> str:
    for th, name in LABELS:
        if p >= th:
            return name
    return LABELS[-1][1]


def _parse(ts: Any) -> datetime | None:
    if ts is None:
        return None
    if isinstance(ts, datetime):
        return ts
    return datetime.fromisoformat(str(ts).replace("Z", ""))


class Estimator:
    """규격. 하위 클래스가 다섯 메서드를 구현한다."""

    name = "abstract"
    version = "0"

    def mastery(self, states: list[State], now: datetime) -> dict[str, float]:
        raise NotImplementedError

    def mastery_at(self, states: list[State], kc: str, now: datetime, dt_days: float) -> float:
        raise NotImplementedError

    def error_pattern(self, states: list[State]) -> dict[str, float]:
        tot: dict[str, float] = {}
        for s in states:
            for t, n in (s.get("n_errors_by_type") or {}).items():
                tot[t] = tot.get(t, 0) + n
        z = sum(tot.values())
        return {t: n / z for t, n in tot.items()} if z else {}

    def p_correct(
        self, states: list[State], item_kcs: list[str], now: datetime, combine: str = "product"
    ) -> float:
        m = self.mastery(states, now)
        ps = [m.get(k, self.prior()) for k in dict.fromkeys(item_kcs)]
        if not ps:
            return self.prior()
        if combine == "min":
            return min(ps)
        if combine == "mean":
            return sum(ps) / len(ps)
        out = 1.0
        for p in ps:
            out *= p
        return out

    def confidence(self, states: list[State]) -> dict[str, int]:
        return {s["kc"]: s.get("n_attempts", 0) for s in states}

    def prior(self) -> float:
        return 0.5

    def labels(self, states: list[State], now: datetime) -> dict[str, str]:
        return {k: label_of(p) for k, p in self.mastery(states, now).items()}


class RuleEstimator(Estimator):
    """1단: policy.py 에 있던 임시 계산식을 규격으로 옮긴 것. 모델 비교의 기준선이자 MVP 첫 버전.
    p = (정답+1)/(시도+2) 에 망각 감쇠 2^(-경과일/반감기), 반감기 = hl0 × growth^(정답 수), 최대 60일.
    """

    name = "rule"
    version = "rule-v1"

    def __init__(self, hl0: float = 1.5, growth: float = 1.7, hl_max: float = 60.0) -> None:
        self.hl0, self.growth, self.hl_max = hl0, growth, hl_max

    def _base(self, s: State) -> float:
        return (s.get("n_correct", 0) + 1) / (s.get("n_attempts", 0) + 2)

    def _halflife(self, s: State) -> float:
        return min(self.hl_max, self.hl0 * self.growth ** s.get("n_correct", 0))

    def _decay(self, p: float, s: State, now: datetime, extra_days: float = 0.0) -> float:
        lp = _parse(s.get("last_practice"))
        if lp is None:
            return p
        days = max(0.0, (now - lp).total_seconds() / 86400) + extra_days
        return FLOOR + (p - FLOOR) * 2 ** (-days / self._halflife(s))

    def mastery(self, states: list[State], now: datetime) -> dict[str, float]:
        return {s["kc"]: self._decay(self._base(s), s, now) for s in states}

    def mastery_at(self, states: list[State], kc: str, now: datetime, dt_days: float) -> float:
        for s in states:
            if s["kc"] == kc:
                return self._decay(self._base(s), s, now, extra_days=dt_days)
        return self.prior()

    def prior(self) -> float:
        return 0.5

    def days_until(self, s: State, now: datetime, threshold: float = 0.70) -> float:
        """이 개념이 threshold 아래로 떨어지기까지 남은 일수 (복습 스케줄용). 이미 아래면 0."""
        p = self._decay(self._base(s), s, now)
        if p <= threshold:
            return 0.0
        base = self._base(s)
        if base <= threshold:
            return 0.0
        hl = self._halflife(s)
        # FLOOR + (base-FLOOR) * 2^(-d/hl) = threshold  →  d
        d_total = -hl * math.log2((threshold - FLOOR) / (base - FLOOR))
        lp = _parse(s.get("last_practice"))
        elapsed = max(0.0, (now - lp).total_seconds() / 86400) if lp else 0.0
        return max(0.0, d_total - elapsed)


class BKTEstimator(RuleEstimator):
    """1단(기본): 개념별 확률 갱신(BKT) + 망각 감쇠.
    상태에 mastery_prob(마지막 관측 직후의 알 확률)를 유지한다 — 갱신은 update() 로 관측 순서대로.
    파라미터는 V2 문서의 BKT_V1 값에서 시작(L0=0.20, T=0.10, G=0.20, S=0.10); 데이터가 쌓이면 맞춘다.
      정답 후: p' = p(1-S) / [p(1-S) + (1-p)G] ,  오답 후: p' = pS / [pS + (1-p)(1-G)] ,  다음 p = p' + (1-p')T
    """

    name = "bkt"
    version = "bkt-forget-v1"

    def __init__(
        self, l0: float = 0.20, t: float = 0.10, g: float = 0.20, s: float = 0.10, **kw: float
    ) -> None:
        super().__init__(**kw)
        self.L0, self.T, self.G, self.S = l0, t, g, s

    def update(self, p: float | None, correct: bool) -> float:
        if p is None:
            p = self.L0
        if correct:
            post = p * (1 - self.S) / (p * (1 - self.S) + (1 - p) * self.G)
        else:
            post = p * self.S / (p * self.S + (1 - p) * (1 - self.G))
        return post + (1 - post) * self.T

    def _base(self, s: State) -> float:
        p = s.get("mastery_prob")
        return float(p) if p is not None else self.L0

    def prior(self) -> float:
        return self.L0


def default_estimator() -> Estimator:
    return BKTEstimator()
