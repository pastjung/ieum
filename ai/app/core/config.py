from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """환경 변수 기반 설정 (.env 지원)"""

    app_name: str = "itda-ai"
    api_prefix: str = "/api/v1"
    debug: bool = True

    # 점자 변환 엔진 버전 — 백엔드·모바일과 채점 기준을 맞추기 위해 응답에 항상 포함한다
    engine_version: str = "v2"
    rule_version: str = "KBR-2024.1"

    model_config = SettingsConfigDict(env_file=".env", env_prefix="ITDA_")


@lru_cache
def get_settings() -> Settings:
    return Settings()
