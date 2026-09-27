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

## 백엔드 연동 (Spring `FastApiClient` 가 부르는 두 엔드포인트 — 루트 경로, 래퍼 없음)

| 메서드 | 경로 | 요청 (Java record) | 응답 (Java record) |
| --- | --- | --- | --- |
| POST | `/analyze` | `RegisterProblemRequest{targetText}` | `ProblemAnalyzeResponse{level, cells[], skillSets[{skillType, metadata}]}` |
| POST | `/reviewAnalyze` | `ReviewAnalyzeRequest{skillStates[]}` | `ReviewAnalyzeResponse{learningSkillIds[]}` |
| POST | `/attempt-analyze` | `{attempt{targetText, expectedPatterns, inputPatterns}, skillStates[]}` | `{feedback{tts, detail}, skillResults[{learningSkillId, correct, errorCounts}], updatedSkillStates[]}` |
| POST | `/session-summary` | `{attempts[], skillStates[]}` | `{summary, ttsText, weakSkills[{learningSkillId, masteryProb, label, dominantErrorType, explanation}]}` |

- `level` 은 **커리큘럼 단계**(CONSONANT·VOWEL·CONTRACTION·CONTRACTION_WORD·WORD·SENTENCE) — `problems.level` 과 같은 의미.
- `skillSets` 의 `(skillType, metadata)` 는 `learning_skills` 시드(`total_init.sql`)와 같은 파일(`app/infrastructure/data/kc_catalog.json`)에서 나온다.
- `/reviewAnalyze` 는 요소별 지금 실력(망각 반영)을 계산해 약한 순 상위 3개 id 만 돌려준다. 어느 단계 문항 풀에서 뽑을지(사용자 레벨, 부족 시 조건 완화)는 DB 를 보는 Spring 의 몫.
- `/attempt-analyze` 는 **문항 하나 제출 직후** 호출: 채점(칸 수가 달라도 정렬) → TTS 피드백 → 요소별 정오(`skillResults`, 이번 문항 오류만) → 학습자 상태 갱신(`updatedSkillStates`, **이번 문항에 나온 요소만**; 처음 만난 요소는 새 행). Spring 은 `attempt_skill_results` INSERT, `user_skill_memory` upsert.
- `/session-summary` 는 **12문항 후** 결과 화면용, 읽기 전용(상태 갱신 없음). `skillStates` 는 마지막 `attempt-analyze` 의 갱신본을 반영한 현재 상태.
- 시각은 `LocalDateTime` 형식(`Z` 없음), 값은 UTC. `Z`·`+09:00` 이 붙어 오면 UTC 로 환산한다.
- 낱자·드릴 문항(`ㄱ`, `ㅙ`, `초성 ㄱ`, `받침 ㄱ`, `모음 ㅏ`, `약자 가`, `약어 그래서`, `음절 아`)도 채점한다(`drill_items.py`). 낱자 `ㄱ`의 첫소리/받침은 `expectedPatterns` 로 판정.
- `skillStates[].recentResults`(선택, 최근 5회 정오 `"01101"`): AI 가 `updatedSkillStates` 에 갱신해 돌려주고 Spring 은 저장·전달만 한다. 학습 모델 특징용.

## 그림자 모델 (학습 모델 도입 준비)

`attempt-analyze` 는 응답을 확률 갱신식(`bkt-forget-v1`)으로 만들고, 같은 요청으로 학습 모델(`lgbm-shadow-v1`, 합성 데이터로
학습한 LightGBM 을 JSON 으로 내보낸 것 — 런타임에 lightgbm 불필요)의 예측도 계산해 **로그에만** 남긴다(`itda.shadow` 로거,
`{"event":"shadow_prediction", ...}` 한 줄). 학습자에게 보이는 값은 바뀌지 않는다.

```bash
docker logs <ai> 2>&1 | grep shadow_prediction > shadow.log
python tools/score_shadow_log.py shadow.log      # 두 모델의 AUC·정확도·로그손실 (실제 학습자 기준)
```
실데이터에서 학습 모델이 앞서면 그때 응답 계산기를 교체한다(`X-Algorithm-Version` 변경).
- 자세한 분석·계약: `docs/repo_analysis_2026-09-23.md`(AI 팀 문서)

```bash
curl -X POST localhost:8000/analyze -H 'content-type: application/json' -d '{"targetText":"가방"}'
# {"level":"CONTRACTION","cells":[43,24,54],"skillSets":[{"skillType":"SKILL_13","metadata":"가"},{"skillType":"SKILL_13","metadata":"바"},{"skillType":"SKILL_03","metadata":"ㅇ"}]}
```

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
├── infrastructure/  # rule_engine.py (규칙 점역기·요소 카탈로그 = 포트 구현체), braille_core/ (한국 점자 규정 제1~18항 엔진), data/
└── presentation/    # FastAPI 라우터 + pydantic 스키마
```

- `domain`은 FastAPI에 의존하지 않는 순수 파이썬.
- 규칙 엔진(`braille_core/kbraille.py`)은 국립국어원 말뭉치 88만 어절·규정 예시 229개로 검증된 것을 그대로 옮겼다. `Protocol` 포트로 주입한다.
- 백엔드 라우터는 `presentation/api/backend/router.py`. 학습 상태 추정(`braille_core/estimate.py`)은 교체 가능한 규격이다.
