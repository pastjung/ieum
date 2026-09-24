# ai — FastAPI

점자 변환 모델·규칙 검증 서비스.

## 실행

```bash
python -m venv .venv
.venv\Scripts\activate          # Windows  (mac/linux: source .venv/bin/activate)
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

- Swagger: `http://localhost:8000/docs`
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
├── domain/
│   ├── braille/     # BrailleCell(VO), ConversionResult, 포트(BrailleConverter, RuleValidator)
│   └── nl/          # IVA 캡슐 자연어: schema, generator, features, trainer, evaluator, model
├── application/     # ConvertService, NlTrainingService (유스케이스)
├── infrastructure/  # StubBrailleConverter, JsonNlModelStore 등 포트 구현체
└── presentation/    # FastAPI 라우터 + pydantic 스키마
scripts/             # train_nl.py — 캡슐 NL 트레이닝 CLI
```

- `domain`은 FastAPI에 의존하지 않는 순수 파이썬.
- 실제 변환 모델(말뭉치 학습)과 규칙 엔진은 `infrastructure`에 구현하고, `Protocol` 포트로 주입한다.

## 캡슐 자연어 트레이닝 (IVA)

모바일 음성 비서 "잇다"([`front/packages/itda`](../front/packages/itda/README.md))가 쓰는 **의도 분류 모델**을 만드는 파이프라인이다.
빅스비 캡슐 개발의 네 번째 단계인 **트레이닝(NL Training)** 에 해당한다.

```
schema.py(액션·concept 정의)
      │
generator.py   템플릿 × concept 값 → 학습 발화 생성
      │        + 말투 변형(~요, 좀 ~)
      │        + 오인식 변형(받침 탈락, ㅅ/ㅆ 혼동, 띄어쓰기 붕괴)
      │        + 캡슐 밖 발화(__ood__) 부정 예시
      │
trainer.py     다항 나이브 베이즈 (문자 2·3-gram + 자모 3-gram)
      │
evaluator.py   holdout / gold(손으로 적은 발화) / OOD 거절 / concept 추출
      │
nl_model_store.py → front/apps/mobile/assets/nl/itda_nl_model.json
```

### 실행

```bash
cd ai && python scripts/train_nl.py
```

학습·평가 리포트를 출력하고, 모델 JSON을 두 곳에 쓴다.

- `ai/artifacts/nl/itda_nl_model.json` — FastAPI가 읽는 사본
- `front/apps/mobile/assets/nl/itda_nl_model.json` — 모바일 앱이 번들에 싣는 사본

앱은 이 파일이 있으면 학습 모델로, 없으면 규칙 NLU로 동작한다. 즉 **학습은 앱 동작의 전제가 아니라 개선 단계**다.

평가만 하려면 `--no-export`, 임계값을 바꾸려면 `--min-confidence 0.6`.

### 디버깅 API

```bash
uvicorn app.main:app --reload --port 8000
curl -X POST localhost:8000/api/v1/nl/parse -H "Content-Type: application/json" \
     -d '{"text":"이번 주 오답 보여줘"}'
```

```json
{"text":"이번 주 오답 보여줘","action":"ShowWrongNotes","confidence":0.99,"accepted":true,
 "concepts":{"ScreenName":"wrong-note","Period":"week"}}
```

`POST /api/v1/nl/train` 으로 서버에서 바로 재학습할 수도 있다.

### 설계 메모

- **피처**: 문자 2·3-gram과 **자모 3-gram**을 함께 쓴다. 자모 단위가 있어야 `복습`/`복십`처럼
  음성 인식이 한 획 틀리게 적어 준 경우를 견딘다. 정의는 앱의 `extractFeatures`(`@ieum/itda`)와 한 글자도 다르면 안 된다.
- **클래스 불균형 보정**: 액션마다 템플릿 수가 달라 학습량이 크게 차이 난다. 보정하지 않으면
  학습 문장이 적은 라벨의 "못 본 피처" 확률이 높아져, 처음 보는 긴 문장이 전부 그 라벨로 끌려간다.
  그래서 라벨별 피처 총량을 같은 값으로 맞춘 뒤 스무딩하고, 사전확률도 균등하게 둔다.
- **OOD 라벨**: 나이브 베이즈는 확신도가 늘 1에 가깝게 나와서 임계값만으로는 캡슐 밖 요청을 못 거른다.
  "날씨 알려줘", "노래 틀어줘" 같은 발화를 `__ood__` 라벨로 함께 학습시켜야 실제로 거절된다.
  앱에서는 이 라벨이 액션 없음으로 매핑된다.
- **concept 추출은 학습하지 않는다**: 사전 매칭 + 숫자 규칙으로 뽑고, 그 사전을 모델 JSON에 함께 실어
  앱과 어휘를 맞춘다. 수업 이름처럼 서버에서 받아야 아는 값은 앱이 실행 중에 사전에 얹는다.

### 테스트

정확도 기준선을 테스트로 박아 두었다. 템플릿이나 사전을 고쳐서 말귀가 나빠지면 여기서 걸린다.

```bash
cd ai && pytest tests/test_nl_training.py -v
```
