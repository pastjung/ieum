from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import get_settings
from app.presentation.api.v1 import convert_router, nl_router


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        debug=settings.debug,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://localhost:8080"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(convert_router.router, prefix=settings.api_prefix)
    app.include_router(nl_router.router, prefix=settings.api_prefix)

    @app.get(f"{settings.api_prefix}/health", tags=["health"])
    def health() -> dict:
        return {"success": True, "data": {"status": "UP", "service": settings.app_name}}

    return app


app = create_app()
