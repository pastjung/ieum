# ai — FastAPI

점자 변환 모델·규칙 검증 서비스.

## 실행

```bash
python -m venv .venv
.venv\Scripts\activate          # Windows  (mac/linux: source .venv/bin/activate)
pip install -r requirements-dev.txt   # 운영 의존성(requirements.txt) + pytest 등 개발 도구
uvicorn app.main:app --reload --port 8000
```

- Swagger: `http://localhost:8000/docs`
- 전체 스택 통합 실행(컨테이너): 루트에서 `docker compose up --build --watch` — [docs/infra/docker.md](../../docs/infra/docker.md)
- Health: `GET /api/v1/health`
- 변환: `POST /api/v1/convert/braille` `{ "text": "가나다" }`

## 테스트

```bash
pytest
```

## DDD 구조

```
app/
├── core/            # 설정 (env)
├── domain/braille/  # BrailleCell(VO), ConversionResult, 포트(BrailleConverter, RuleValidator)
├── application/     # ConvertService (변환 → 규칙 검증 유스케이스)
├── infrastructure/  # StubBrailleConverter 등 포트 구현체 (→ 말뭉치 모델·규칙 엔진으로 교체)
└── presentation/    # FastAPI 라우터 + pydantic 스키마
```

- `domain`은 FastAPI에 의존하지 않는 순수 파이썬.
- 실제 변환 모델(말뭉치 학습)과 규칙 엔진은 `infrastructure`에 구현하고, `Protocol` 포트로 주입한다.
