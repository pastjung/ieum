"""캡슐 NL 트레이닝 CLI.

    python scripts/train_nl.py                 # 학습 + 평가 + 모델 내보내기
    python scripts/train_nl.py --no-export     # 평가만
    python scripts/train_nl.py --min-confidence 0.6

내보낸 JSON은 안드로이드 앱 assets에 바로 들어가고, 앱은 다음 실행부터 그 모델을 쓴다.
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

# 저장소 루트에서 바로 실행할 수 있도록 ai/ 를 import 경로에 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.application.nl_training_service import NlTrainingService  # noqa: E402
from app.infrastructure.nl_model_store import JsonNlModelStore  # noqa: E402


def main() -> int:
    # 윈도우 콘솔 기본 인코딩(cp949)에서 한글 리포트가 깨지지 않게 한다.
    if isinstance(sys.stdout, io.TextIOWrapper):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="잇다 캡슐 자연어 트레이닝")
    parser.add_argument("--seed", type=int, default=20260901)
    parser.add_argument("--alpha", type=float, default=0.3, help="라플라스 스무딩 계수")
    parser.add_argument("--min-confidence", type=float, default=0.55)
    parser.add_argument("--holdout", type=float, default=0.15)
    parser.add_argument("--version", default="1")
    parser.add_argument("--no-export", action="store_true", help="모델 파일을 쓰지 않는다")
    args = parser.parse_args()

    service = NlTrainingService(store=JsonNlModelStore())
    outcome = service.run(
        seed=args.seed,
        alpha=args.alpha,
        min_confidence=args.min_confidence,
        holdout_ratio=args.holdout,
        version=args.version,
        export=not args.no_export,
    )

    print(outcome.summary())

    gold = next(report for report in outcome.reports if report.name.startswith("gold"))
    return 0 if gold.accuracy >= 0.7 else 1


if __name__ == "__main__":
    raise SystemExit(main())
