"""tools/score_shadow_log.py — 그림자 로그 채점: 확률 갱신식(BKT) vs 학습 모델(LightGBM), 실제 학습자 기준.

사용: docker logs <ai-container> 2>&1 | grep shadow_prediction > shadow.log
      python tools/score_shadow_log.py shadow.log
출력: 요소 관측 수, 각 모델의 AUC(0.5=찍기, 1=완벽)·정확도·평균 로그손실. 의존성 없음.
"""

import json
import math
import sys


def auc(scores: list[float], labels: list[int]) -> float:
    pos = sorted(s for s, y in zip(scores, labels, strict=True) if y)
    neg = sorted(s for s, y in zip(scores, labels, strict=True) if not y)
    if not pos or not neg:
        return float("nan")
    import bisect

    tot = 0.0
    for p in pos:
        lo, hi = bisect.bisect_left(neg, p), bisect.bisect_right(neg, p)
        tot += lo + 0.5 * (hi - lo)
    return tot / (len(pos) * len(neg))


def main(path: str) -> None:
    rows: list[tuple[float, float, int]] = []
    for line in open(path, encoding="utf-8"):
        i = line.find("{")
        if i < 0:
            continue
        try:
            rec = json.loads(line[i:])
        except json.JSONDecodeError:
            continue
        if rec.get("event") != "shadow_prediction":
            continue
        for s in rec["skills"]:
            rows.append((float(s["p_bkt"]), float(s["p_lgbm"]), int(bool(s["correct"]))))
    if not rows:
        print("shadow_prediction 줄이 없습니다")
        return
    y = [r[2] for r in rows]
    print(f"요소 관측 {len(rows):,}건, 정답률 {sum(y) / len(y):.3f}")
    for name, idx in (("bkt-forget-v1", 0), ("lgbm-shadow-v1", 1)):
        p = [r[idx] for r in rows]
        acc = sum((pi >= 0.5) == bool(yi) for pi, yi in zip(p, y, strict=True)) / len(y)
        ll = -sum(
            yi * math.log(max(pi, 1e-6)) + (1 - yi) * math.log(max(1 - pi, 1e-6))
            for pi, yi in zip(p, y, strict=True)
        ) / len(y)
        print(f"  {name:16} AUC {auc(p, y):.3f}  정확도 {acc:.3f}  로그손실 {ll:.3f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "shadow.log")
