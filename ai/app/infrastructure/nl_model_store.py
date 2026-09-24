"""학습 모델 JSON 저장소.

두 곳에 같은 파일을 쓴다.
- `ai/artifacts/nl/itda_nl_model.json` — 서버(FastAPI)가 읽는 사본
- `front/apps/mobile/assets/nl/itda_nl_model.json` — 모바일 앱이 번들에 싣는 사본

앱은 이 파일이 있으면 학습 모델로, 없으면 규칙 NLU로 동작한다.
"""

from __future__ import annotations

import json
from pathlib import Path

from app.domain.nl.model import NlModel

# ai/app/infrastructure/nl_model_store.py -> parents[3] == 저장소 루트
REPO_ROOT = Path(__file__).resolve().parents[3]

AI_ARTIFACT = REPO_ROOT / "ai" / "artifacts" / "nl" / "itda_nl_model.json"
MOBILE_ASSET = (
    REPO_ROOT / "front" / "apps" / "mobile" / "assets" / "nl" / "itda_nl_model.json"
)


class JsonNlModelStore:
    def __init__(self, targets: list[Path] | None = None) -> None:
        self.targets = targets if targets is not None else [AI_ARTIFACT, MOBILE_ASSET]

    def save(self, model: NlModel) -> list[Path]:
        payload = json.dumps(model.to_dict(), ensure_ascii=False, separators=(",", ":"))
        written: list[Path] = []
        for target in self.targets:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(payload, encoding="utf-8")
            written.append(target)
        return written

    def load(self) -> NlModel | None:
        for target in self.targets:
            if target.exists():
                return NlModel.from_dict(json.loads(target.read_text(encoding="utf-8")))
        return None
