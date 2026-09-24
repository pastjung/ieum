# @ieum/itda — 음성 비서 "잇다"의 두뇌

받아쓴 문장을 읽고 **무엇을 할지** 정한다. 마이크·소리·블루투스는 건드리지 않는다.

```
받아쓴 문장 ─ NluPipeline ─▶ (action, concepts) ─ ItdaCapsule ─▶ 말할 것 + 갈 곳 + 모듈 명령
```

순수 TypeScript다(React/RN/브라우저 API 없음). 그래서 **기기 없이 전부 테스트된다** —
mobile 코드 규약의 "BLE 로직은 화면과 분리된 모듈로 작성한다"를 따른 것이다.

## 구성

| 파일 | 역할 |
| --- | --- |
| `hangul.ts` | 정규화·자모 분해·편집 거리. 인식 결과가 흔들려도 견디게 하는 바탕 |
| `wakeWord.ts` | "하이 잇다" 판정 (자모 편집 거리) |
| `nlu/features.ts` | 문자 2·3-gram + 자모 3-gram — **학습 쪽과 정의가 같아야 한다** |
| `nlu/model.ts` | 학습 모델 JSON 해석 + 점수 계산 (다항 나이브 베이즈) |
| `nlu/concepts.ts` | 사전 매칭으로 concept 추출 (화면·난이도·기간·문항 수·점 번호) |
| `nlu/ruleNlu.ts` | 규칙 기준선 — 모델이 없거나 확신이 낮을 때 |
| `nlu/pipeline.ts` | 모델 우선, 규칙 폴백 |
| `capsule/model.ts` | ① 모델링 — 액션·concept·화면 정의 |
| `capsule/capsule.ts` | ② 비즈니스 로직 — 무엇을 할지 결정 |
| `capsule/session.ts` | 되묻기 상태를 포함한 한 사이클 |
| `capsule/tactile.ts` | 촉각 모듈 명령·포트 (아두이노 `iot/braille_solenoid` 프로토콜) |

## 쓰는 쪽에서 해야 할 일

이 패키지는 **결정만** 한다. 앱 계층이 받아서 실행한다.

```ts
const pipeline = NluPipeline.create(modelJson);        // 모델이 없으면 null을 넘겨도 된다
const session = new CapsuleSession(() => pipeline, new ItdaCapsule({ tactile }));

const { nlu, result } = await session.handle("3번 점 올려줘");
// result.kind === "done" → speech를 읽어 주고, navigation대로 router.push
// result.kind === "needsConcept" → speech로 되묻고 다음 발화를 그대로 다시 handle에 넘긴다
```

- **말하기**는 `src/a11y/speech.ts`의 `speak()`를 거친다 (TTS 정책 일원화 지점)
- **이동**은 `navigation.route`를 expo-router에 넘긴다
- **촉각 모듈**은 `TactileDevice`를 구현해 생성자에 주입한다. 기기가 없으면 `InMemoryTactileDevice`가 기본값

## 화면을 추가·이동했다면

`capsule/model.ts`의 `DESTINATIONS`만 고친다. `id`는 학습 모델의 ScreenName 값이라 유지하고,
`route`만 바꾸면 음성 이동이 따라온다.

## 학습 모델

`NluPipeline.create()`에 JSON을 넘기면 학습 모델을 쓰고, 넘기지 않으면 규칙 NLU로 동작한다.
**모델은 정확도를 올리는 단계이지 전제가 아니다.** 모델 사전은 앱 기본 사전 **위에** 얹히므로,
모델이 모르는 화면도 기본 사전 덕분에 계속 인식된다.

모델은 `ai/scripts/train_nl.py`가 만들어
`front/apps/mobile/assets/nl/itda_nl_model.json`에 바로 써 주고, 앱은 그 JSON을 번들에 싣는다.

피처 정의(`nlu/features.ts`)는 학습 파이프라인(`ai/app/domain/nl/features.py`)과 한 글자도
다르면 안 된다 — 어긋나면 학습한 가중치를 못 찾아 정확도가 조용히 무너진다.
`DESTINATIONS`의 id도 `ai/app/domain/nl/schema.py`의 `SCREEN_VALUES` 키와 같아야 한다.

## 테스트

```bash
cd front/apps/mobile && pnpm test
```
