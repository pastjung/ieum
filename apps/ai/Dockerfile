# ---------- base: 운영 의존성 ----------
FROM python:3.12-slim AS base

WORKDIR /srv

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ---------- dev: 로컬 개발용 (루트 docker-compose.yml이 target: dev로 사용) ----------
# 소스는 compose가 볼륨 마운트하고, --reload가 파일 변경 시 자동 재시작한다.
# dev 의존성(pytest 등)도 설치해 컨테이너 안에서 테스트를 실행할 수 있다.
FROM base AS dev

COPY requirements-dev.txt .
RUN pip install --no-cache-dir -r requirements-dev.txt

# 기동 시점의 소스 (이후 변경은 compose watch sync가 반영)
COPY app app

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]

# ---------- test: MR 검증 파이프라인용 (docker build --target test) ----------
FROM base AS test

COPY requirements-dev.txt .
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements-dev.txt

COPY app app
COPY tests tests
COPY pyproject.toml .

# 린트 → 포맷 검사 → 타입체크 → 테스트 (하나라도 실패하면 빌드 실패 = verify ❌)
RUN ruff check . && ruff format --check . && pyright && python -m pytest

# ---------- runtime: 운영 이미지 (기본 빌드 대상) ----------
FROM base AS runtime

COPY app app

RUN addgroup --system fastapi && adduser --system --ingroup fastapi fastapi
USER fastapi

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
