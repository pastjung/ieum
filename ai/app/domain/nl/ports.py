"""NL 도메인 포트 — 저장/내보내기 구현은 infrastructure가 제공한다."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

from app.domain.nl.model import NlModel


class NlModelStore(Protocol):
    def save(self, model: NlModel) -> list[Path]:
        """학습한 모델을 내보내고, 쓴 파일 경로를 돌려준다."""
        ...

    def load(self) -> NlModel | None:
        """가장 최근에 내보낸 모델. 없으면 None."""
        ...
