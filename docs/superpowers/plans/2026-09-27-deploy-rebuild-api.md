# 운영 배포 재구축 — backend 음성 합성 대리 호출 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학습자 앱이 Cartesia를 직접 부르지 않도록, backend에 `POST /api/v1/voice/clones`(WAV → voiceId)와 `POST /api/v1/voice/speech`(문장 → MP3)를 만들고, 키가 없을 때 503·사용량 초과 429·Cartesia 실패 502를 계약대로 돌려준다. 계약 문서(api-spec)와 오류 code를 먼저 고친다.

**Architecture:** 새 컨텍스트 `com.itda.voice` 를 lecture와 같은 모양으로 둔다 — `controller`(HTTP 변환만), `dto`, `service`(역할·설정·입력·사용량 판정 순서와 오류 code 결정), `cartesia`(외부 호출 계약 `VoiceSynthesisGateway` 와 RestClient 구현·설정), `repository`(Redis 사용량 카운터 `VoiceRateLimiter`). Cartesia 요청 모양(헤더·multipart 파트·`ttsRequestBody`)은 앱 코드 `front/apps/mobile/src/services/cartesia.ts` 의 현재 값을 그대로 옮긴다. HTTP 통합 테스트는 게이트웨이·사용량 제한기를 `@Primary` 대역으로 바꿔 네트워크·Redis 없이 돌고, 실제 Redis 카운터는 `@RequiresRealRedis` 테스트가 따로 증명한다.

**Tech Stack:** Spring Boot 4.0.8, Java 25, Spring MVC(`RestClient` + `JdkClientHttpRequestFactory`, `MultipartBodyBuilder`), Jackson 3(`tools.jackson.*`, 애너테이션은 `com.fasterxml.jackson.annotation`), Spring Data Redis(`StringRedisTemplate` + Lua `RedisScript`), JUnit 5 + AssertJ, `MockRestServiceServer`, `@WebApiTest`(실제 Tomcat + JDK HttpClient), `@RequiresRealRedis`

**Spec:** `docs/superpowers/specs/2026-09-27-deploy-rebuild-design.md` §6(음성 합성 대리 호출), §8(완료 기준) / 교차 계약 `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/deploy-contract.md` 「Voice synthesis proxy」(infra·front 계획과 공유 — 경로·필드 이름·상태·오류 code·Redis 키를 바꾸지 않는다)

## Global Constraints

- 브랜치 `chore/deploy-rebuild-api`(`chore/deploy-rebuild` 에서 분기), 워크트리 `C:/Users/SSAFY/Desktop/wt-deploy-api`. 끝나면 사람이 `chore/deploy-rebuild` 로 **로컬 병합**한다(MR은 `chore/deploy-rebuild` → dev 한 번). **push·MR 생성은 하지 않는다** — 이 계획은 로컬 커밋까지만
- 이 브랜치가 고치는 파일은 `apps/backend/**` 와 `docs/product/api-spec.md` 뿐이다. `deploy/`·`Jenkinsfile`·`scripts/`·`docs/infra/`·`front/` 는 다른 계획의 몫이다(직교 수정 금지)
- 커밋: `type(scope): 한글 제목`, scope는 `voice`(Task 6만 `infra`). 마지막 줄은 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 이슈 번호가 주어지지 않았으므로 `Refs #번호` 는 생략한다(받으면 제목 아래 빈 줄 뒤 `Refs #번호`, 그 다음 Co-Authored-By)
- `git push --force`·`--no-verify` 금지. `.env` 계열·`*.pem` 은 읽지도 수정하지도 않는다. EC2 서버에 상태를 바꾸는 명령을 보내지 않는다
- 새 환경변수는 `CARTESIA_API_KEY` 하나. **선택값**이다 — 비어 있어도 앱이 기동해야 하고, 호출 시점에 503 `VOICE_SYNTHESIS_UNAVAILABLE` 이다. `.env.example`·`deploy/.env.config.example` 은 비밀 파일 규칙상 에이전트가 고치지 않는다 — 최종 보고에 "사람이 키 이름을 추가"로 적는다
- 계약 값(글자 그대로):
  - `POST /api/v1/voice/clones` — LEARNER 전용. `multipart/form-data`, 파트 `clip` = WAV 파일(≤ 10 MB), 선택 파트 `name`(≤ 50자, 기본 `"내 목소리"`). 201 `{"voiceId":"<string>"}`
  - `POST /api/v1/voice/speech` — LEARNER 전용. JSON `{"voiceId":"<1..100자>","text":"<앞뒤 공백 제거 후 1..300자>"}`, 위반은 400 `INVALID_REQUEST_PARAMETER`. 200 body = MP3 바이트, `Content-Type: audio/mpeg`
  - 401 `UNAUTHORIZED`(토큰 없음·불량), 403 `LEARNER_ACCESS_REQUIRED`(LEARNER 아님)
  - 503 `VOICE_SYNTHESIS_UNAVAILABLE` — 키 미설정 / 429 `VOICE_RATE_LIMITED` — 학습자별 1분 30회 초과(두 API가 한도를 공유) / 502 `VOICE_SYNTHESIS_FAILED` — Cartesia 오류·시간 초과(원문은 응답에 넣지 않는다)
  - Redis 키 `voice:rate:{userId}:{epochMinute}`(중괄호는 자리표시, 예: `voice:rate:42:29811360`), TTL 120초
  - Cartesia base `https://api.cartesia.ai`, `Cartesia-Version: 2026-08-14`, 모델 `sonic-3.6`, 언어 `ko`, 출력 `{"container":"mp3","sample_rate":44100,"bit_rate":128000}`. 제한 시간 복제 60초·합성 20초
- **인증 헤더는 `Authorization: Bearer <key>` 를 쓴다.** 교차 계약에는 `X-API-Key` 로 적혀 있지만, 같은 계약이 "cartesia.ts에서 그대로 옮긴다"고 정하고 cartesia.ts 주석이 "실제 호출로 확인했다 — 인증 `Authorization: Bearer`"라고 적는다(`X-API-Key` 는 옛 API 버전 방식). backend 내부 값이라 다른 계획에 영향이 없다. 최종 보고에 이 결정을 적는다
- 오류 code는 계약 문서(api-spec)에 먼저 적고 `ErrorCode` 에 추가한다(`ErrorCode.java` 주석 규칙) — Task 1이 둘을 한 커밋에 담는다
- context-path가 `/api/v1` 이므로 매핑·SecurityConfig에는 그 뒤 경로(`/voice/clones`, `/voice/speech`)만 적는다. **새 경로는 모두 `SecurityConfig` 보호 matcher에 넣는다** — 빠지면 principal이 null이라 401이 아니라 500이 된다
- **컨트롤러 클래스에 `@Validated` 를 붙이지 않는다.** 또 `@Valid @RequestBody` 만 쓰면 `MethodArgumentNotValidException` → 422 `INVALID_REQUEST_INPUT` 이 되어 계약(400)과 어긋나고, "앞뒤 공백 제거 후 1~300자"는 Bean Validation으로 표현되지 않는다. 그래서 음성 입력 검증은 서비스가 직접 하고 `BusinessException(INVALID_REQUEST_PARAMETER)` 를 던진다
- 판정 순서(서비스): 역할(403) → 키 설정(503) → 입력(400) → 사용량(429) → Cartesia 호출(502). 입력 오류로 거절한 요청은 사용량을 세지 않고, Cartesia가 실패한 요청은 센다. 사용량 저장소(Redis) 장애는 503 `VOICE_SYNTHESIS_UNAVAILABLE` 로 끊는다(fail-closed — 키 비용을 지키기 위해서)
- Cartesia 응답 본문·`RestClientResponseException.getMessage()`(본문이 들어 있다)를 로그·예외 message·HTTP 응답 어디에도 싣지 않는다. 로그에는 경로와 HTTP 상태 숫자 또는 예외 클래스 이름만 남긴다
- 기존 `RestClient` bean(`RestClientConfig.restClient`, FastAPI용)이 하나뿐이라는 전제를 깨지 않는다 — Cartesia용 `RestClient` 는 **bean으로 등록하지 않고** 설정 클래스 안에서 만들어 게이트웨이에 넘긴다(`PasswordSecurityConfiguration` 과 같은 방식). bean으로 두면 `FastApiClient` 주입이 모호해진다
- **커밋 전 검증:** `cd apps/backend && ./gradlew test` BUILD SUCCESSFUL. 실패 상태로 커밋하지 않는다
- **포맷:** 커밋 전 `./gradlew spotlessApply` → `git status --short`. 이 Task가 건드리지 않은 파일이 바뀌어 있으면 `git restore -- <그 파일>` 로 되돌리고 `./gradlew spotlessCheck` 를 다시 돌린다(직교 수정 금지)
- `docker build --target test apps/backend` 는 `mariadb-test` 스테이지에서 dev에서 넘어온 `UserWithdrawal` 계열 19건(커밋 `18846616`)이 이미 실패한다 — **이 계획의 범위 밖**이다. 통합 검증은 `docker build --target redis-test apps/backend`(spotlessCheck + test + 실제 Redis 테스트 skipped=0 확인)로 한다

## File Structure

(Java 경로 기준 `apps/backend/src/main/java/com/itda/`, 테스트 `apps/backend/src/test/java/com/itda/`)

| 파일 | 책임 |
| --- | --- |
| `docs/product/api-spec.md` (수정) | §1 범위 문장에 음성 합성 2개 추가, §12 「음성 합성 대리 호출」 신설(두 API·판정 순서·사용량·오류 표) |
| `global/error/ErrorCode.java` (수정) | `VOICE_SYNTHESIS_UNAVAILABLE`(503), `VOICE_RATE_LIMITED`(429), `VOICE_SYNTHESIS_FAILED`(502) |
| `voice/cartesia/CartesiaProperties.java` (신규) | `itda.cartesia.*` 설정. 키만 비어도 되고 나머지는 기동 단계 검증 |
| `voice/cartesia/VoiceSynthesisGateway.java` (신규) | 외부 음성 합성 계약(`isAvailable`·`cloneVoice`·`synthesize`) |
| `voice/cartesia/VoiceSynthesisException.java` (신규) | Cartesia 호출 실패. message에 응답 원문을 싣지 않는다 |
| `voice/cartesia/CartesiaVoiceGateway.java` (신규) | RestClient 구현. 앱과 같은 헤더·multipart·`ttsRequestBody` |
| `voice/cartesia/CartesiaConfiguration.java` (신규) | 복제용(60초)·합성용(20초) RestClient를 만들어 게이트웨이 bean 조립 |
| `voice/repository/VoiceRateLimiter.java` (신규) | 사용량 카운터 계약 |
| `voice/repository/RedisVoiceRateLimiter.java` (신규) | Redis INCR+EXPIRE(Lua 한 번) 구현 |
| `src/main/resources/redis/increment-voice-rate.lua` (신규) | 카운터 증가와 첫 증가 때 TTL을 원자적으로 |
| `voice/service/VoiceSynthesisService.java` (신규) | 판정 순서, 입력 검증, 오류 code 변환 |
| `voice/dto/VoiceCloneResponse.java`·`VoiceSpeechRequest.java` (신규) | HTTP DTO |
| `voice/controller/VoiceController.java` (신규) | 두 경로, 201·`audio/mpeg` |
| `global/security/SecurityConfig.java` (수정) | 보호 경로 2개 |
| `global/error/GlobalExceptionHandler.java` (수정) | multipart 한도 초과 413(body 없음), multipart 해석 실패 400 |
| `src/main/resources/application.yml` (수정) | `spring.servlet.multipart` 한도, `itda.cartesia.*`, prod 프로필 Redis 접속(Task 6) |
| 테스트 | `global/error/ErrorCodeTest`(수정), `voice/cartesia/{CartesiaPropertiesTest,CartesiaVoiceGatewayTest}`, `voice/repository/RedisVoiceRateLimiterRedisTest`, `voice/service/{VoiceSynthesisServiceTest,RecordingVoiceSynthesisGateway,StubVoiceRateLimiter,VoiceTestClips}`, `voice/controller/VoiceApiIntegrationTest` |

### 확인한 코드·환경 (2026-09-27, 저장소 코드로 확인)

- `front/apps/mobile/src/services/cartesia.ts`: `BASE_URL = "https://api.cartesia.ai"`, `API_VERSION = "2026-08-14"`, `MODEL_ID = "sonic-3.6"`, `LANGUAGE = "ko"`. 복제 multipart 파트 `clip`(파일 이름 `voice-sample.wav`, `audio/wav`)·`name`·`language`=`ko`·`description`=`잇다 사용자 목소리`, 응답 JSON의 `id`. 합성 `POST /tts/bytes` JSON `{model_id, transcript, voice:{id}, language, output_format:{container:"mp3", sample_rate:44100, bit_rate:128000}}`. 헤더 `Authorization: Bearer`, `Cartesia-Version`, JSON일 때만 `Content-Type: application/json`. 앱의 녹음(`voiceSample.ts` `buildWav`)은 실제 RIFF/WAVE 파일이다 → 서버가 WAV 머리글(`RIFF`…`WAVE`)만 확인해도 앱이 깨지지 않는다
- 인증(`AccessAuthenticationService`)은 MariaDB만 보고 Redis를 쓰지 않는다 → HTTP 통합 테스트는 Redis 없이 돈다(기존 `LectureAuthenticatedApiIntegrationTest` 와 같음)
- `GlobalExceptionHandler`: `MethodArgumentNotValidException` → 422, `HandlerMethodValidationException` 등 → 400, 마지막 `Exception` → 500. `MultipartException`·`MaxUploadSizeExceededException` 처리기가 없어 지금은 500이 된다 → Task 5에서 추가
- Spring Boot multipart 기본 `max-file-size` 는 1MB다 → 10MB로 올리지 않으면 계약을 지킬 수 없다
- `StringRedisTemplate` 은 Boot 자동 구성 bean이다(`RefreshTokenRedisConfiguration` 이 주입받아 쓴다). Lua는 `src/main/resources/redis/*.lua` 로 두고 `RedisScript.of(new ClassPathResource(...), 타입)` 으로 읽는 것이 이 저장소 규칙이다. 스크립트를 bean으로 두지 않는다 — 기존 `RedisScript<String>` bean들은 파라미터 이름 주입에 기대고 있다
- 테스트 Redis 사용자 id 대역: 8_000_000_000·9_000_000_000·9_100_000_000·9_200_000_000이 이미 쓰인다 → 이 계획은 9_500_000_000에서 시작한다
- `application.yml` prod 프로필에는 `spring.data.redis.host` 가 없다(주석: "운영 Redis 연결은 배포 작업에서 prod 프로필에 별도로 추가한다"). 설계 §3.2는 `.env.config` 에 `REDIS_HOST=redis`·`REDIS_PORT=6379` 를 넣는다 → 이 키를 읽게 하는 것이 backend 몫이라 Task 6에 둔다(없으면 운영에서 인증·사용량 제한 모두 `localhost:6379` 로 붙어 실패한다)

---

### Task 0: 워크트리와 브랜치

**Files:** 없음

- [ ] **Step 1: 워크트리 생성**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git worktree add -b chore/deploy-rebuild-api C:/Users/SSAFY/Desktop/wt-deploy-api chore/deploy-rebuild
```

Expected: `Preparing worktree (new branch 'chore/deploy-rebuild-api')`. 브랜치·경로가 이미 있으면 멈추고 사람에게 확인한다(덮어쓰지 않는다). 메인 폴더의 추적되지 않은 `seed_remaining.sql`·`total_init.sql` 은 건드리지 않는다.

- [ ] **Step 2: 기준선 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-api
git log --oneline -1   # dea509a9 docs(infra): 운영 배포 재구축 설계를 정한다 (또는 그 뒤 chore/deploy-rebuild HEAD)
cd apps/backend && ./gradlew test spotlessCheck
```

Expected: BUILD SUCCESSFUL. 실패하면 변경 전 상태이므로 원인을 보고하고 멈춘다.

이후 모든 Task는 `C:/Users/SSAFY/Desktop/wt-deploy-api` 에서 한다.

---

### Task 1: 계약 문서와 오류 code

**Files:**
- Modify: `docs/product/api-spec.md` (§1 둘째 문단, 파일 끝에 §12 추가)
- Modify: `apps/backend/src/main/java/com/itda/global/error/ErrorCode.java`
- Test: `apps/backend/src/test/java/com/itda/global/error/ErrorCodeTest.java`

**Interfaces:**
- Produces: `ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE`(503), `ErrorCode.VOICE_RATE_LIMITED`(429), `ErrorCode.VOICE_SYNTHESIS_FAILED`(502)

- [ ] **Step 1: api-spec §1 범위 문장 수정**

기존(8행):

```markdown
이 문서는 계정·개인 학습·문제·수업의 HTTP 요청·응답·권한·오류·멱등 계약을 정의한다.
```

새 문장(같은 줄의 앞부분만 바꾼다. 뒤 문장은 그대로):

```markdown
이 문서는 계정·개인 학습·문제·수업·음성 합성의 HTTP 요청·응답·권한·오류·멱등 계약을 정의한다.
```

기존(10행):

```markdown
개인 학습 6개, 계정·인증 10개, 문제·기준 정보 5개, 수업 12개의 공개 HTTP와 2개의 AI 내부 HTTP 계약을 다룬다.
```

새 문장(같은 줄의 앞부분만 바꾼다):

```markdown
개인 학습 6개, 계정·인증 10개, 문제·기준 정보 5개, 수업 12개, 음성 합성 2개의 공개 HTTP와 2개의 AI 내부 HTTP 계약을 다룬다.
```

- [ ] **Step 2: api-spec 끝에 §12 추가**

`## 11. 계약 검수 기준` 절의 마지막 문단 뒤(파일 끝)에 빈 줄 하나를 두고 다음을 붙인다.

```markdown
## 12. 음성 합성 대리 호출

학습자 앱의 "내 목소리"는 Cartesia 음성 복제·합성을 쓴다. Cartesia API 키를 앱(APK)에 넣지 않기 위해 Backend가 대신 호출한다. Backend는 녹음·voiceId·문장·합성 결과를 저장하지 않는다 — voiceId와 합성 결과 캐시는 앱이 보관한다.

| method·path | 성공 | 권한·동작 |
| --- | --- | --- |
| `POST /api/v1/voice/clones` | 201 | LEARNER. `multipart/form-data`: `clip`(WAV 파일, 필수, 최대 10MB), `name`(선택, 앞뒤 공백 제거 후 최대 50자, 없거나 비면 `내 목소리`). Cartesia 목소리 복제 결과를 `{"voiceId":"..."}` 로 돌려준다 |
| `POST /api/v1/voice/speech` | 200 | LEARNER. JSON `{"voiceId":"...","text":"..."}`. `voiceId` 1~100자, `text` 앞뒤 공백 제거 후 1~300자(제거한 문장을 합성한다). body는 MP3 바이트, `Content-Type: audio/mpeg` |

- 판정 순서: 인증(401) → 역할(403) → 키 설정(503) → 입력(400) → 사용량(429) → Cartesia 호출(502).
- 사용량: 학습자별 UTC 분(epoch minute) 단위 30회, 두 API가 한도를 공유한다. Redis 키 `voice:rate:{userId}:{epochMinute}`, TTL 120초. 입력 오류로 거절한 요청은 세지 않고, Cartesia 호출이 실패한 요청은 센다.
- 설정: `CARTESIA_API_KEY` 는 Backend 설정에만 둔다. 비어 있어도 Backend는 기동하며 두 API가 503을 돌려준다.
- Cartesia 호출 제한 시간: 복제 60초, 합성 20초. 앱의 요청 제한 시간은 이보다 길어야 한다.
- Cartesia의 오류 응답·예외 원문은 응답·로그에 넣지 않는다. 로그에는 호출 경로와 HTTP 상태만 남긴다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST_PARAMETER | `clip` 없음·빈 파일·WAV 아님, `name` 50자 초과, `voiceId`·`text` 길이 위반, multipart 본문 해석 실패 |
| 401 | UNAUTHORIZED | 인증되지 않음·유효하지 않은 Access JWT |
| 403 | LEARNER_ACCESS_REQUIRED | 요청자가 LEARNER가 아님 |
| 413 | (body 없음) | `clip` 10MB 초과 — 공통 code가 없는 상태이므로 상태만 유지한다 (2.2절) |
| 415 | UNSUPPORTED_CONTENT_TYPE | `clones` 에 `multipart/form-data` 가 아닌 요청 |
| 429 | VOICE_RATE_LIMITED | 이번 분의 사용량 30회를 넘음 |
| 502 | VOICE_SYNTHESIS_FAILED | Cartesia 오류 응답·시간 초과·연결 실패·빈 결과 |
| 503 | VOICE_SYNTHESIS_UNAVAILABLE | `CARTESIA_API_KEY` 미설정, 사용량 저장소(Redis) 장애 |
```

- [ ] **Step 3: 실패하는 테스트 작성**

`ErrorCodeTest.java` 의 import 블록에 추가:

```java
import org.springframework.http.HttpStatus;
```

클래스 마지막 메서드 뒤에 추가:

```java
  @Test
  void 음성_합성_오류는_API_명세_12절의_상태를_쓴다() {
    assertThat(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE.getStatus())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(ErrorCode.VOICE_RATE_LIMITED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.VOICE_SYNTHESIS_FAILED.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
  }
```

- [ ] **Step 4: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests com.itda.global.error.ErrorCodeTest`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol ... VOICE_SYNTHESIS_UNAVAILABLE`

- [ ] **Step 5: ErrorCode 추가**

`ErrorCode.java` 에서 다음 줄

```java
  MEDIA_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "음성 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
```

바로 뒤에 추가:

```java

  // voice (docs/product/api-spec.md 12절)
  /** {@code CARTESIA_API_KEY} 미설정 또는 사용량 저장소 장애. 앱은 "목소리 기능을 쓸 수 없어요" 안내로 바꾼다. */
  VOICE_SYNTHESIS_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "목소리 기능을 지금 쓸 수 없어요. 잠시 후 다시 시도해 주세요."),
  /** 학습자별 1분 30회(두 음성 API 합산)를 넘었다. */
  VOICE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),
  /** Cartesia 오류·시간 초과. Cartesia 응답 원문은 message에 넣지 않는다. */
  VOICE_SYNTHESIS_FAILED(HttpStatus.BAD_GATEWAY, "목소리를 만들지 못했어요. 잠시 후 다시 시도해 주세요."),
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew test --tests com.itda.global.error.ErrorCodeTest`
Expected: PASS (파라미터화된 `code는_UPPER_SNAKE_CASE이고_HTTP_상태를_숫자로_되풀이하지_않는다` 도 세 값에 대해 통과)

- [ ] **Step 7: 전체 테스트·포맷·커밋**

```bash
./gradlew spotlessApply && git status --short   # ErrorCode·ErrorCodeTest·api-spec 외 변경이 있으면 git restore -- <파일>
./gradlew test spotlessCheck
cd ../..
git add docs/product/api-spec.md apps/backend/src/main/java/com/itda/global/error/ErrorCode.java apps/backend/src/test/java/com/itda/global/error/ErrorCodeTest.java
git commit -m "$(cat <<'EOF'
feat(voice): 음성 합성 대리 호출 계약과 오류 code를 정한다

api-spec 12절에 목소리 복제·합성 API 두 개, 판정 순서, 사용량 한도,
오류 표를 적고 VOICE_SYNTHESIS_UNAVAILABLE·VOICE_RATE_LIMITED·
VOICE_SYNTHESIS_FAILED를 ErrorCode에 추가한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Cartesia 설정과 게이트웨이

**Files:**
- Create: `apps/backend/src/main/java/com/itda/voice/cartesia/CartesiaProperties.java`
- Create: `apps/backend/src/main/java/com/itda/voice/cartesia/VoiceSynthesisGateway.java`
- Create: `apps/backend/src/main/java/com/itda/voice/cartesia/VoiceSynthesisException.java`
- Create: `apps/backend/src/main/java/com/itda/voice/cartesia/CartesiaVoiceGateway.java`
- Create: `apps/backend/src/main/java/com/itda/voice/cartesia/CartesiaConfiguration.java`
- Modify: `apps/backend/src/main/resources/application.yml` (`itda.livekit` 블록 뒤)
- Test: `apps/backend/src/test/java/com/itda/voice/cartesia/CartesiaPropertiesTest.java`
- Test: `apps/backend/src/test/java/com/itda/voice/cartesia/CartesiaVoiceGatewayTest.java`

**Interfaces:**
- Produces: `public record CartesiaProperties(String baseUrl, String apiKey, String apiVersion, Duration connectTimeout, Duration cloneTimeout, Duration speechTimeout)` — `apiKey` 는 null이면 `""`, 앞뒤 공백 제거
- Produces: `public interface VoiceSynthesisGateway { boolean isAvailable(); String cloneVoice(byte[] wav, String name); byte[] synthesize(String voiceId, String text); }` — 두 호출은 실패 시 `VoiceSynthesisException` 을 던진다
- Produces: `public class VoiceSynthesisException extends RuntimeException { public VoiceSynthesisException(String message) }`
- Produces: `public CartesiaVoiceGateway(RestClient cloneClient, RestClient speechClient, String apiKey, String apiVersion)`
- Produces: bean `VoiceSynthesisGateway voiceSynthesisGateway(CartesiaProperties)` (`CartesiaConfiguration`, package-private 클래스)

- [ ] **Step 1: 설정 테스트 작성**

`CartesiaPropertiesTest.java`:

```java
package com.itda.voice.cartesia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CartesiaPropertiesTest {

  private static final String BASE_URL = "https://api.cartesia.ai";
  private static final String VERSION = "2026-08-14";
  private static final Duration CONNECT = Duration.ofSeconds(5);
  private static final Duration CLONE = Duration.ofSeconds(60);
  private static final Duration SPEECH = Duration.ofSeconds(20);

  @Test
  void API_키가_없어도_기동을_막지_않고_빈_문자열로_둔다() {
    CartesiaProperties properties =
        new CartesiaProperties(BASE_URL, null, VERSION, CONNECT, CLONE, SPEECH);

    assertThat(properties.apiKey()).isEmpty();
  }

  @Test
  void API_키의_앞뒤_공백은_지운다() {
    CartesiaProperties properties =
        new CartesiaProperties(BASE_URL, "  key-1  ", VERSION, CONNECT, CLONE, SPEECH);

    assertThat(properties.apiKey()).isEqualTo("key-1");
  }

  @Test
  void 주소가_없으면_기동_단계에서_실패한다() {
    assertThatThrownBy(
            () -> new CartesiaProperties(" ", "key", VERSION, CONNECT, CLONE, SPEECH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.cartesia.base-url");
  }

  @Test
  void 버전_헤더_값이_없으면_기동_단계에서_실패한다() {
    assertThatThrownBy(
            () -> new CartesiaProperties(BASE_URL, "key", null, CONNECT, CLONE, SPEECH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.cartesia.api-version");
  }

  @Test
  void 제한_시간이_0이하면_기동_단계에서_실패한다() {
    assertThatThrownBy(
            () ->
                new CartesiaProperties(BASE_URL, "key", VERSION, CONNECT, Duration.ZERO, SPEECH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itda.cartesia.clone-timeout");
  }
}
```

- [ ] **Step 2: 게이트웨이 테스트 작성**

`CartesiaVoiceGatewayTest.java`:

```java
package com.itda.voice.cartesia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 실제 Cartesia를 호출하지 않는다. 요청 모양은 앱(front/apps/mobile/src/services/cartesia.ts)이 보내던 값과 같아야 한다. */
class CartesiaVoiceGatewayTest {

  private static final String BASE_URL = "https://api.cartesia.test";
  private static final String API_KEY = "test-cartesia-key";
  private static final String API_VERSION = "2026-08-14";
  private static final String UPSTREAM_DETAIL = "upstream-secret-detail";
  private static final byte[] WAV = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44};

  private MockRestServiceServer server;
  private CartesiaVoiceGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    this.server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    this.gateway = new CartesiaVoiceGateway(client, client, API_KEY, API_VERSION);
  }

  /** multipart 본문에서 한 파트의 값을 꺼낸다. 파트 머리글 다음 빈 줄부터 다음 경계 앞까지다. */
  private static String partValue(String body, String partName) {
    int header = body.indexOf("name=\"" + partName + "\"");
    assertThat(header).as(partName + " 파트").isNotNegative();
    int start = body.indexOf("\r\n\r\n", header) + 4;
    int end = body.indexOf("\r\n--", start);
    return body.substring(start, end);
  }

  @Test
  void 복제는_앱과_같은_헤더와_multipart로_보내고_voice_id를_돌려준다() {
    this.server
        .expect(requestTo(BASE_URL + "/voices/clone"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
        .andExpect(header("Cartesia-Version", API_VERSION))
        .andExpect(
            request -> {
              assertThat(request.getHeaders().getContentType())
                  .satisfies(
                      type -> assertThat(type.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue());
              String body =
                  ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
              assertThat(body)
                  .contains("name=\"clip\"; filename=\"voice-sample.wav\"")
                  .contains("Content-Type: audio/wav");
              assertThat(partValue(body, "name")).isEqualTo("내 목소리");
              assertThat(partValue(body, "language")).isEqualTo("ko");
              assertThat(partValue(body, "description")).isEqualTo("잇다 사용자 목소리");
            })
        .andRespond(
            withSuccess(
                "{\"id\":\"voice-123\",\"name\":\"내 목소리\",\"language\":\"ko\"}",
                MediaType.APPLICATION_JSON));

    assertThat(this.gateway.cloneVoice(WAV, "내 목소리")).isEqualTo("voice-123");
    this.server.verify();
  }

  @Test
  void 복제_응답에_id가_없으면_실패다() {
    this.server
        .expect(requestTo(BASE_URL + "/voices/clone"))
        .andRespond(withSuccess("{\"name\":\"내 목소리\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> this.gateway.cloneVoice(WAV, "내 목소리"))
        .isInstanceOf(VoiceSynthesisException.class);
  }

  @Test
  void 합성은_앱의_ttsRequestBody와_같은_JSON을_보내고_MP3_바이트를_돌려준다() {
    this.server
        .expect(requestTo(BASE_URL + "/tts/bytes"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
        .andExpect(header("Cartesia-Version", API_VERSION))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {"model_id":"sonic-3.6","transcript":"안녕하세요","voice":{"id":"voice-123"},
                     "language":"ko",
                     "output_format":{"container":"mp3","sample_rate":44100,"bit_rate":128000}}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess(MP3, MediaType.parseMediaType("audio/mpeg")));

    assertThat(this.gateway.synthesize("voice-123", "안녕하세요")).isEqualTo(MP3);
    this.server.verify();
  }

  @Test
  void 합성_결과가_비어_있으면_실패다() {
    this.server
        .expect(requestTo(BASE_URL + "/tts/bytes"))
        .andRespond(withSuccess(new byte[0], MediaType.parseMediaType("audio/mpeg")));

    assertThatThrownBy(() -> this.gateway.synthesize("voice-123", "안녕"))
        .isInstanceOf(VoiceSynthesisException.class);
  }

  @Test
  void Cartesia_오류_응답의_본문은_예외_message에_싣지_않는다() {
    this.server
        .expect(requestTo(BASE_URL + "/tts/bytes"))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .body(UPSTREAM_DETAIL)
                .contentType(MediaType.TEXT_PLAIN));

    assertThatThrownBy(() -> this.gateway.synthesize("voice-123", "안녕"))
        .isInstanceOf(VoiceSynthesisException.class)
        .hasMessageContaining("401")
        .hasMessageNotContaining(UPSTREAM_DETAIL);
  }

  @Test
  void 연결_실패나_시간_초과는_VoiceSynthesisException이다() {
    ClientHttpRequestFactory timingOut =
        (uri, httpMethod) -> {
          throw new SocketTimeoutException("Read timed out");
        };
    RestClient client = RestClient.builder().baseUrl(BASE_URL).requestFactory(timingOut).build();
    CartesiaVoiceGateway timedOut = new CartesiaVoiceGateway(client, client, API_KEY, API_VERSION);

    assertThatThrownBy(() -> timedOut.synthesize("voice-123", "안녕"))
        .isInstanceOf(VoiceSynthesisException.class);
    assertThatThrownBy(() -> timedOut.cloneVoice(WAV, "내 목소리"))
        .isInstanceOf(VoiceSynthesisException.class);
  }

  @Test
  void API_키가_비어_있으면_호출할_수_없는_상태다() {
    RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

    assertThat(new CartesiaVoiceGateway(client, client, "", API_VERSION).isAvailable()).isFalse();
    assertThat(this.gateway.isAvailable()).isTrue();
  }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests "com.itda.voice.cartesia.*"`
Expected: FAIL — `compileTestJava` 에서 `package com.itda.voice.cartesia does not exist` / `cannot find symbol CartesiaVoiceGateway`

- [ ] **Step 4: 설정 record 작성**

`CartesiaProperties.java`:

```java
package com.itda.voice.cartesia;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code itda.cartesia.*} (API 명세 12절).
 *
 * <p>API 키만 비어 있어도 된다. 키가 없으면 Backend는 기동하고 두 음성 API가 503을 돌려준다 — 음성 기능 하나 때문에 계정·수업 전체가 뜨지 않으면
 * 안 된다. 주소·버전·제한 시간은 빠지면 기동 단계에서 실패한다.
 *
 * @param cloneTimeout 복제 응답 대기. 앱 실측으로 업로드+처리가 12~14초 걸린다
 */
@ConfigurationProperties(prefix = "itda.cartesia")
public record CartesiaProperties(
    String baseUrl,
    String apiKey,
    String apiVersion,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("60s") Duration cloneTimeout,
    @DefaultValue("20s") Duration speechTimeout) {

  public CartesiaProperties {
    requireValue(baseUrl, "itda.cartesia.base-url");
    requireValue(apiVersion, "itda.cartesia.api-version");
    apiKey = apiKey == null ? "" : apiKey.strip();
    requirePositive(connectTimeout, "itda.cartesia.connect-timeout");
    requirePositive(cloneTimeout, "itda.cartesia.clone-timeout");
    requirePositive(speechTimeout, "itda.cartesia.speech-timeout");
  }

  private static void requireValue(String value, String name) {
    if (value == null || value.isBlank() || value.startsWith("${")) {
      throw new IllegalArgumentException(name + " 설정이 필요합니다.");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " 설정은 0보다 커야 합니다.");
    }
  }
}
```

- [ ] **Step 5: 게이트웨이 계약과 예외 작성**

`VoiceSynthesisGateway.java`:

```java
package com.itda.voice.cartesia;

/** 목소리 복제·합성 외부 서비스. 구현은 Cartesia이며 테스트는 대역으로 바꾼다. */
public interface VoiceSynthesisGateway {

  /** API 키가 설정돼 있어 호출할 수 있는가. false면 호출하지 않고 503으로 끝낸다. */
  boolean isAvailable();

  /**
   * WAV 녹음 하나로 목소리를 복제한다.
   *
   * @return 외부 서비스의 voice id
   * @throws VoiceSynthesisException 오류 응답·시간 초과·연결 실패·id 없는 응답
   */
  String cloneVoice(byte[] wav, String name);

  /**
   * 복제한 목소리로 문장을 읽는다.
   *
   * @return MP3 바이트 (비어 있지 않다)
   * @throws VoiceSynthesisException 오류 응답·시간 초과·연결 실패·빈 결과
   */
  byte[] synthesize(String voiceId, String text);
}
```

`VoiceSynthesisException.java`:

```java
package com.itda.voice.cartesia;

/**
 * 외부 음성 서비스 호출 실패.
 *
 * <p>원인 예외를 붙이지 않는다. {@code RestClientResponseException} 의 message에는 Cartesia 응답 본문이 그대로 들어 있어, 누군가
 * 이 예외를 로그로 찍으면 본문이 새어 나간다.
 */
public class VoiceSynthesisException extends RuntimeException {

  public VoiceSynthesisException(String message) {
    super(message);
  }
}
```

- [ ] **Step 6: Cartesia 구현 작성**

`CartesiaVoiceGateway.java`:

```java
package com.itda.voice.cartesia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cartesia 음성 복제 API (API 명세 12절).
 *
 * <ul>
 *   <li>{@code POST /voices/clone} WAV 샘플을 올리고 voice id를 받는다
 *   <li>{@code POST /tts/bytes} 그 voice id로 문장을 읽어 MP3를 받는다
 * </ul>
 *
 * <p>요청 모양은 앱(front/apps/mobile/src/services/cartesia.ts)이 실제 호출로 확인한 값을 그대로 옮긴 것이다 — 인증 {@code
 * Authorization: Bearer}, 버전 헤더 {@code Cartesia-Version}, 모델 {@code sonic-3.6}. 응답은 MP3로 받는다: WAV로 받으면 스트리밍용
 * 헤더가 붙어(RIFF 크기 0xFFFFFFFF, data 앞 LIST 청크) 앱의 플레이어가 열지 못한다.
 */
@Slf4j
public class CartesiaVoiceGateway implements VoiceSynthesisGateway {

  static final String CLONE_PATH = "/voices/clone";
  static final String SPEECH_PATH = "/tts/bytes";
  static final String VERSION_HEADER = "Cartesia-Version";
  static final String MODEL_ID = "sonic-3.6";
  static final String LANGUAGE = "ko";
  static final String CLIP_FILENAME = "voice-sample.wav";
  static final MediaType CLIP_TYPE = MediaType.parseMediaType("audio/wav");
  static final String VOICE_DESCRIPTION = "잇다 사용자 목소리";

  private final RestClient cloneClient;
  private final RestClient speechClient;
  private final String apiKey;
  private final String apiVersion;

  /**
   * @param cloneClient 복제용. 응답 대기 시간이 합성과 달라 client를 나눈다
   * @param speechClient 합성용
   */
  public CartesiaVoiceGateway(
      RestClient cloneClient, RestClient speechClient, String apiKey, String apiVersion) {
    this.cloneClient = cloneClient;
    this.speechClient = speechClient;
    this.apiKey = apiKey;
    this.apiVersion = apiVersion;
  }

  @Override
  public boolean isAvailable() {
    return !this.apiKey.isBlank();
  }

  @Override
  public String cloneVoice(byte[] wav, String name) {
    MultipartBodyBuilder form = new MultipartBodyBuilder();
    form.part("clip", new ByteArrayResource(wav)).filename(CLIP_FILENAME).contentType(CLIP_TYPE);
    form.part("name", name);
    form.part("language", LANGUAGE);
    form.part("description", VOICE_DESCRIPTION);

    CloneResponse response =
        call(
            CLONE_PATH,
            () ->
                this.cloneClient
                    .post()
                    .uri(CLONE_PATH)
                    .headers(this::authenticate)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form.build())
                    .retrieve()
                    .body(CloneResponse.class));
    if (response == null || response.id() == null || response.id().isBlank()) {
      throw failure(CLONE_PATH, "응답에 voice id 없음");
    }
    return response.id();
  }

  @Override
  public byte[] synthesize(String voiceId, String text) {
    byte[] audio =
        call(
            SPEECH_PATH,
            () ->
                this.speechClient
                    .post()
                    .uri(SPEECH_PATH)
                    .headers(this::authenticate)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.ALL)
                    .body(SpeechRequest.of(voiceId, text))
                    .retrieve()
                    .body(byte[].class));
    if (audio == null || audio.length == 0) {
      throw failure(SPEECH_PATH, "빈 합성 결과");
    }
    return audio;
  }

  private void authenticate(HttpHeaders headers) {
    headers.setBearerAuth(this.apiKey);
    headers.set(VERSION_HEADER, this.apiVersion);
  }

  private static <T> T call(String path, Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      // e.getMessage()에는 응답 본문이 들어 있다. 상태 숫자만 남긴다.
      throw failure(path, "HTTP " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw failure(path, e.getClass().getSimpleName());
    }
  }

  private static VoiceSynthesisException failure(String path, String reason) {
    log.warn("Cartesia {} 호출 실패: {}", path, reason);
    return new VoiceSynthesisException("Cartesia " + path + " 호출 실패: " + reason);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CloneResponse(String id) {}

  /** 앱의 {@code ttsRequestBody} 와 같은 필드. Cartesia는 snake_case를 받는다. */
  record SpeechRequest(
      @JsonProperty("model_id") String modelId,
      String transcript,
      Voice voice,
      String language,
      @JsonProperty("output_format") OutputFormat outputFormat) {

    static SpeechRequest of(String voiceId, String text) {
      return new SpeechRequest(
          MODEL_ID, text, new Voice(voiceId), LANGUAGE, new OutputFormat("mp3", 44_100, 128_000));
    }
  }

  record Voice(String id) {}

  record OutputFormat(
      String container,
      @JsonProperty("sample_rate") int sampleRate,
      @JsonProperty("bit_rate") int bitRate) {}
}
```

- [ ] **Step 7: 설정 클래스와 application.yml**

`CartesiaConfiguration.java`:

```java
package com.itda.voice.cartesia;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cartesia 게이트웨이 조립.
 *
 * <p>{@link RestClient} 를 bean으로 두지 않는다. FastAPI용 {@code RestClientConfig.restClient} 가 유일한 {@code RestClient}
 * bean이라는 전제로 {@code FastApiClient} 가 타입 주입을 받기 때문이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartesiaProperties.class)
class CartesiaConfiguration {

  @Bean
  VoiceSynthesisGateway voiceSynthesisGateway(CartesiaProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    return new CartesiaVoiceGateway(
        restClient(httpClient, properties.baseUrl(), properties.cloneTimeout()),
        restClient(httpClient, properties.baseUrl(), properties.speechTimeout()),
        properties.apiKey(),
        properties.apiVersion());
  }

  private static RestClient restClient(HttpClient httpClient, String baseUrl, Duration readTimeout) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
```

`application.yml` — 다음 세 줄

```yaml
    url: ${LIVEKIT_URL}
    api-key: ${LIVEKIT_API_KEY}
    api-secret: ${LIVEKIT_API_SECRET}
```

바로 뒤(`  mail:` 앞)에 추가:

```yaml
  # Cartesia 음성 복제·합성 (API 명세 12절). APK에 키를 넣지 않기 위해 학습자 앱 대신 backend가 호출한다.
  # 키는 선택값이다 — 비어 있으면 기동은 하고 두 음성 API가 503 VOICE_SYNTHESIS_UNAVAILABLE을 돌려준다.
  cartesia:
    base-url: https://api.cartesia.ai
    api-key: ${CARTESIA_API_KEY:}
    # 앱(front/apps/mobile/src/services/cartesia.ts)이 실제 호출로 확인한 API 버전. 따옴표는 날짜로 읽히지 않게 하려는 것이다.
    api-version: "2026-08-14"
    connect-timeout: 5s
    clone-timeout: 60s
    speech-timeout: 20s
```

- [ ] **Step 8: 통과 확인**

Run: `./gradlew test --tests "com.itda.voice.cartesia.*"`
Expected: PASS (7 + 5 tests)

만약 `content().json(..., JsonCompareMode.STRICT)` 가 필드 순서 때문에 실패한다고 보이면 원인이 다르다 — JSONAssert STRICT는 순서가 아니라 필드 집합·배열 순서만 본다. 실패 message의 `Expected`/`got` 필드를 보고 record 필드 이름(`@JsonProperty`)을 확인한다.

- [ ] **Step 9: 전체 테스트·포맷·커밋**

```bash
./gradlew spotlessApply && git status --short   # 이 Task 파일 외 변경은 git restore -- <파일>
./gradlew test spotlessCheck                     # CARTESIA_API_KEY 없이 컨텍스트가 뜨는 것도 여기서 확인된다
cd ../..
git add apps/backend/src/main/java/com/itda/voice/cartesia apps/backend/src/test/java/com/itda/voice/cartesia apps/backend/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(voice): Cartesia 목소리 복제·합성 게이트웨이를 추가한다

앱이 보내던 헤더·multipart 파트·ttsRequestBody를 그대로 옮겨 backend가
Cartesia를 부른다. 키는 선택 설정이라 비어 있어도 기동한다. 오류 응답
본문은 로그·예외 message에 싣지 않는다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 사용량 제한 (Redis)

**Files:**
- Create: `apps/backend/src/main/java/com/itda/voice/repository/VoiceRateLimiter.java`
- Create: `apps/backend/src/main/java/com/itda/voice/repository/RedisVoiceRateLimiter.java`
- Create: `apps/backend/src/main/resources/redis/increment-voice-rate.lua`
- Test: `apps/backend/src/test/java/com/itda/voice/repository/RedisVoiceRateLimiterRedisTest.java`

**Interfaces:**
- Produces: `public interface VoiceRateLimiter { boolean tryAcquire(long userId); }` — 이번 호출을 세고, 이번 분의 30회 안이면 true. 저장소 장애는 `org.springframework.dao.DataAccessException` 으로 던진다
- Produces: `@Component public class RedisVoiceRateLimiter` — `static final int LIMIT_PER_MINUTE = 30`, `static final Duration KEY_TTL = Duration.ofSeconds(120)`, `static String key(long userId, long epochMinute)`, 테스트용 package-private 생성자 `RedisVoiceRateLimiter(StringRedisTemplate, Clock)`

- [ ] **Step 1: 실패하는 Redis 테스트 작성**

`RedisVoiceRateLimiterRedisTest.java` (이름이 `RedisTest` 로 끝나야 Dockerfile이 skipped=0을 확인한다):

```java
package com.itda.voice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.support.redis.RequiresRealRedis;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 음성 사용량 카운터를 실제 Redis에 대고 검증한다 (API 명세 12절). INCR과 첫 증가 때의 EXPIRE가 한 Lua 호출로 묶였는지, 키 모양과 TTL이 계약과
 * 같은지는 가짜 구현으로는 증명되지 않는다.
 *
 * <p>FLUSHDB를 쓰지 않는다. 이 테스트가 만든 키만 지운다.
 */
@SpringBootTest
@RequiresRealRedis
class RedisVoiceRateLimiterRedisTest {

  /** 다른 Redis 테스트(8_000_000_000·9_000_000_000·9_100_000_000·9_200_000_000 대역)와 겹치지 않는 대역. */
  private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(9_500_000_000L);

  /** 분의 중간 시각. 테스트 도중 분이 바뀌어 흔들리지 않게 시계를 고정한다. */
  private static final Instant NOW = Instant.parse("2026-09-27T12:00:30Z");

  @Autowired private StringRedisTemplate redis;

  private long userId;
  private final List<String> createdKeys = new ArrayList<>();

  @BeforeEach
  void setUp() {
    this.userId = USER_ID_SEQUENCE.incrementAndGet();
  }

  @AfterEach
  void deleteOnlyKeysCreatedHere() {
    this.redis.delete(this.createdKeys);
  }

  private RedisVoiceRateLimiter limiterAt(Instant instant, long forUser) {
    String key = RedisVoiceRateLimiter.key(forUser, instant.getEpochSecond() / 60);
    this.createdKeys.add(key);
    this.redis.delete(key); // 앞선 실행이 남긴 키가 있으면 지우고 시작한다
    return new RedisVoiceRateLimiter(this.redis, Clock.fixed(instant, ZoneOffset.UTC));
  }

  @Test
  void 같은_분에는_30회까지_허용하고_31번째를_거절한다() {
    RedisVoiceRateLimiter limiter = limiterAt(NOW, this.userId);

    for (int call = 1; call <= RedisVoiceRateLimiter.LIMIT_PER_MINUTE; call++) {
      assertThat(limiter.tryAcquire(this.userId)).as("%d번째 호출", call).isTrue();
    }
    assertThat(limiter.tryAcquire(this.userId)).isFalse();
  }

  @Test
  void 카운터_키는_계약_모양이고_TTL은_120초_이하로_붙는다() {
    RedisVoiceRateLimiter limiter = limiterAt(NOW, this.userId);

    limiter.tryAcquire(this.userId);
    limiter.tryAcquire(this.userId);

    String key = "voice:rate:" + this.userId + ":" + (NOW.getEpochSecond() / 60);
    assertThat(this.redis.opsForValue().get(key)).isEqualTo("2");
    assertThat(this.redis.getExpire(key)).isBetween(1L, 120L);
  }

  @Test
  void 다음_분에는_새로_센다() {
    RedisVoiceRateLimiter thisMinute = limiterAt(NOW, this.userId);
    for (int call = 0; call < RedisVoiceRateLimiter.LIMIT_PER_MINUTE; call++) {
      thisMinute.tryAcquire(this.userId);
    }
    assertThat(thisMinute.tryAcquire(this.userId)).isFalse();

    RedisVoiceRateLimiter nextMinute = limiterAt(NOW.plusSeconds(60), this.userId);

    assertThat(nextMinute.tryAcquire(this.userId)).isTrue();
  }

  @Test
  void 학습자마다_따로_센다() {
    long otherUserId = USER_ID_SEQUENCE.incrementAndGet();
    RedisVoiceRateLimiter limiter = limiterAt(NOW, this.userId);
    limiterAt(NOW, otherUserId);
    for (int call = 0; call <= RedisVoiceRateLimiter.LIMIT_PER_MINUTE; call++) {
      limiter.tryAcquire(this.userId);
    }

    assertThat(limiter.tryAcquire(otherUserId)).isTrue();
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests com.itda.voice.repository.RedisVoiceRateLimiterRedisTest`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol RedisVoiceRateLimiter`. (로컬에 Redis가 없으면 컴파일 뒤 SKIPPED가 된다 — 그때는 `docker compose up -d redis` 로 띄운 뒤 Step 4를 확인한다. 저장소 루트 `docker-compose.yml` 의 redis 서비스다.)

- [ ] **Step 3: 구현 작성**

`increment-voice-rate.lua`:

```lua
-- 음성 합성 사용량 카운터 (docs/product/api-spec.md 12절).
-- KEYS[1] = voice:rate:{userId}:{epochMinute}, ARGV[1] = TTL(초)
-- INCR과 EXPIRE를 한 번에 실행해, 두 명령 사이에 연결이 끊겨 TTL 없는 키가 남는 일을 막는다.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return count
```

`VoiceRateLimiter.java`:

```java
package com.itda.voice.repository;

/** 음성 합성 사용량. 복제·합성 두 API가 한도를 공유한다 (API 명세 12절). */
public interface VoiceRateLimiter {

  /**
   * 이번 호출을 센다.
   *
   * @return 이번 분의 한도 안이면 true
   * @throws org.springframework.dao.DataAccessException 저장소 장애
   */
  boolean tryAcquire(long userId);
}
```

`RedisVoiceRateLimiter.java`:

```java
package com.itda.voice.repository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 학습자별 UTC 분 단위 카운터. 키 {@code voice:rate:{userId}:{epochMinute}}, TTL 120초 (API 명세 12절).
 *
 * <p>TTL을 한 분보다 길게 두는 이유: 분이 바뀌는 순간에 도착한 증가가 이미 지워진 키를 되살려 TTL 없이 남지 않게 여유를 준다. 지난 분의 키는
 * 더 이상 읽지 않으므로 늦게 지워져도 결과가 바뀌지 않는다.
 *
 * <p>인증 상태 키와 달리 환경 prefix를 붙이지 않는다 — 키 모양은 교차 계약이 정했고, 운영 Redis는 이 서비스 전용이다.
 */
@Component
public class RedisVoiceRateLimiter implements VoiceRateLimiter {

  static final int LIMIT_PER_MINUTE = 30;
  static final Duration KEY_TTL = Duration.ofSeconds(120);

  /** bean으로 두지 않는다. 기존 {@code RedisScript<String>} bean들이 파라미터 이름 주입에 기대고 있다. */
  private static final RedisScript<Long> INCREMENT_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/increment-voice-rate.lua"), Long.class);

  private final StringRedisTemplate redis;
  private final Clock clock;

  @Autowired
  public RedisVoiceRateLimiter(StringRedisTemplate redis) {
    this(redis, Clock.systemUTC());
  }

  RedisVoiceRateLimiter(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  @Override
  public boolean tryAcquire(long userId) {
    long epochMinute = Math.floorDiv(this.clock.instant().getEpochSecond(), 60);
    Long count =
        this.redis.execute(
            INCREMENT_SCRIPT,
            List.of(key(userId, epochMinute)),
            Long.toString(KEY_TTL.toSeconds()));
    return count != null && count <= LIMIT_PER_MINUTE;
  }

  static String key(long userId, long epochMinute) {
    return "voice:rate:" + userId + ":" + epochMinute;
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests com.itda.voice.repository.RedisVoiceRateLimiterRedisTest`
Expected: PASS (4 tests, Redis가 떠 있을 때). 결과 파일 `build/test-results/test/TEST-com.itda.voice.repository.RedisVoiceRateLimiterRedisTest.xml` 에 `skipped="0"` 인지 확인한다.

- [ ] **Step 5: 전체 테스트·포맷·커밋**

```bash
./gradlew spotlessApply && git status --short
./gradlew test spotlessCheck
cd ../..
git add apps/backend/src/main/java/com/itda/voice/repository apps/backend/src/main/resources/redis/increment-voice-rate.lua apps/backend/src/test/java/com/itda/voice/repository
git commit -m "$(cat <<'EOF'
feat(voice): 학습자별 음성 합성 사용량을 Redis로 센다

voice:rate:{userId}:{epochMinute} 키를 Lua 한 번으로 INCR하고 첫 증가 때
TTL 120초를 붙인다. 분당 30회를 넘으면 거절한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 음성 합성 서비스 (판정 순서·입력 검증·오류 변환)

**Files:**
- Create: `apps/backend/src/main/java/com/itda/voice/service/VoiceSynthesisService.java`
- Create (test fixture): `apps/backend/src/test/java/com/itda/voice/service/RecordingVoiceSynthesisGateway.java`
- Create (test fixture): `apps/backend/src/test/java/com/itda/voice/service/StubVoiceRateLimiter.java`
- Create (test fixture): `apps/backend/src/test/java/com/itda/voice/service/VoiceTestClips.java`
- Test: `apps/backend/src/test/java/com/itda/voice/service/VoiceSynthesisServiceTest.java`

**Interfaces:**
- Consumes: `VoiceSynthesisGateway`, `VoiceSynthesisException` (Task 2), `VoiceRateLimiter` (Task 3), `ErrorCode.VOICE_*` (Task 1), `ErrorCode.LEARNER_ACCESS_REQUIRED`, `ErrorCode.INVALID_REQUEST_PARAMETER`, `AuthenticatedUser(long userId, UserRole role)`
- Produces: `@Service public class VoiceSynthesisService` — `public VoiceSynthesisService(VoiceSynthesisGateway, VoiceRateLimiter)`, `public String cloneVoice(AuthenticatedUser user, byte[] clip, String name)`, `public byte[] synthesize(AuthenticatedUser user, String voiceId, String text)`. 모든 실패는 `BusinessException`
- Produces (test fixtures, Task 5가 재사용): `RecordingVoiceSynthesisGateway`(`VOICE_ID`·`MP3`·`UPSTREAM_DETAIL` 상수, `available`·`failing` 스위치, `clonedNames`·`clonedClipSizes`·`spoken` 기록, `reset()`), `StubVoiceRateLimiter`(`allowing`·`failure` 스위치, `acquiredBy` 기록, `reset()`), `VoiceTestClips.wav(int size)`

- [ ] **Step 1: 테스트 대역 작성**

`VoiceTestClips.java`:

```java
package com.itda.voice.service;

import java.nio.charset.StandardCharsets;

/** 음성 테스트용 녹음 바이트. */
public final class VoiceTestClips {

  private VoiceTestClips() {}

  /** {@code RIFF....WAVE} 머리글만 갖춘 size 바이트짜리 WAV. 서버는 머리글만 확인한다. size는 13 이상. */
  public static byte[] wav(int size) {
    byte[] clip = new byte[size];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, clip, 0, 4);
    System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, clip, 8, 4);
    return clip;
  }
}
```

`RecordingVoiceSynthesisGateway.java`:

```java
package com.itda.voice.service;

import com.itda.voice.cartesia.VoiceSynthesisException;
import com.itda.voice.cartesia.VoiceSynthesisGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 호출을 기록하는 게이트웨이 대역. HTTP 테스트에서는 요청 스레드가 쓰고 테스트 스레드가 읽으므로 동시성 안전한 목록과 volatile 스위치를 쓴다.
 * context를 공유하므로 쓰는 쪽이 {@link #reset()} 으로 되돌린다.
 */
public class RecordingVoiceSynthesisGateway implements VoiceSynthesisGateway {

  public static final String VOICE_ID = "voice-fake-1";
  public static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44};

  /** 실패 때 예외 message에 넣는 가짜 Cartesia 원문. 응답에 이 문자열이 보이면 누출이다. */
  public static final String UPSTREAM_DETAIL = "Cartesia 401: upstream-secret-detail";

  public volatile boolean available = true;
  public volatile boolean failing;
  public final List<String> clonedNames = new CopyOnWriteArrayList<>();
  public final List<Integer> clonedClipSizes = new CopyOnWriteArrayList<>();

  /** {@code "{voiceId}/{text}"} */
  public final List<String> spoken = new CopyOnWriteArrayList<>();

  public void reset() {
    this.available = true;
    this.failing = false;
    this.clonedNames.clear();
    this.clonedClipSizes.clear();
    this.spoken.clear();
  }

  @Override
  public boolean isAvailable() {
    return this.available;
  }

  @Override
  public String cloneVoice(byte[] wav, String name) {
    if (this.failing) {
      throw new VoiceSynthesisException(UPSTREAM_DETAIL);
    }
    this.clonedNames.add(name);
    this.clonedClipSizes.add(wav.length);
    return VOICE_ID;
  }

  @Override
  public byte[] synthesize(String voiceId, String text) {
    if (this.failing) {
      throw new VoiceSynthesisException(UPSTREAM_DETAIL);
    }
    this.spoken.add(voiceId + "/" + text);
    return MP3.clone();
  }
}
```

`StubVoiceRateLimiter.java`:

```java
package com.itda.voice.service;

import com.itda.voice.repository.VoiceRateLimiter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 허용·거절·장애를 스위치로 고르는 사용량 제한기 대역. 실제 Redis 카운터는 RedisVoiceRateLimiterRedisTest가 검증한다. */
public class StubVoiceRateLimiter implements VoiceRateLimiter {

  public volatile boolean allowing = true;

  /** null이 아니면 이 예외를 던진다 — 저장소 장애 흉내. */
  public volatile RuntimeException failure;

  public final List<Long> acquiredBy = new CopyOnWriteArrayList<>();

  public void reset() {
    this.allowing = true;
    this.failure = null;
    this.acquiredBy.clear();
  }

  @Override
  public boolean tryAcquire(long userId) {
    RuntimeException toThrow = this.failure;
    if (toThrow != null) {
      throw toThrow;
    }
    this.acquiredBy.add(userId);
    return this.allowing;
  }
}
```

- [ ] **Step 2: 실패하는 서비스 테스트 작성**

`VoiceSynthesisServiceTest.java`:

```java
package com.itda.voice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.user.domain.UserRole;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.RedisConnectionFailureException;

class VoiceSynthesisServiceTest {

  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(7L, UserRole.LEARNER);
  private static final byte[] CLIP = VoiceTestClips.wav(64);

  private final RecordingVoiceSynthesisGateway gateway = new RecordingVoiceSynthesisGateway();
  private final StubVoiceRateLimiter rateLimiter = new StubVoiceRateLimiter();
  private final VoiceSynthesisService service = new VoiceSynthesisService(gateway, rateLimiter);

  private static ErrorCode errorOf(ThrowingCallable call) {
    return catchThrowableOfType(BusinessException.class, call).getErrorCode();
  }

  // ---------- 역할·설정 ----------

  @ParameterizedTest
  @EnumSource(value = UserRole.class, names = {"INSTRUCTOR", "ADMIN"})
  void 학습자가_아니면_LEARNER_ACCESS_REQUIRED이고_사용량을_세지_않는다(UserRole role) {
    AuthenticatedUser user = new AuthenticatedUser(8L, role);

    assertThat(errorOf(() -> service.cloneVoice(user, CLIP, null)))
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertThat(errorOf(() -> service.synthesize(user, "voice-1", "안녕")))
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void 키가_없으면_입력_검증보다_먼저_503이다() {
    gateway.available = false;

    assertThat(errorOf(() -> service.cloneVoice(LEARNER, new byte[0], null)))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertThat(errorOf(() -> service.synthesize(LEARNER, "", "")))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  // ---------- 복제 입력 ----------

  @Test
  void 이름을_생략하거나_공백만_보내면_내_목소리로_복제한다() {
    assertThat(service.cloneVoice(LEARNER, CLIP, null))
        .isEqualTo(RecordingVoiceSynthesisGateway.VOICE_ID);
    service.cloneVoice(LEARNER, CLIP, "   ");

    assertThat(gateway.clonedNames).containsExactly("내 목소리", "내 목소리");
    assertThat(gateway.clonedClipSizes).containsExactly(64, 64);
    assertThat(rateLimiter.acquiredBy).containsExactly(7L, 7L);
  }

  @Test
  void 이름은_앞뒤_공백을_지우고_50자까지_받는다() {
    String fifty = "가".repeat(50);

    service.cloneVoice(LEARNER, CLIP, "  " + fifty + "  ");

    assertThat(gateway.clonedNames).containsExactly(fifty);
  }

  @Test
  void 이름이_50자를_넘으면_400이고_사용량을_세지_않는다() {
    assertThat(errorOf(() -> service.cloneVoice(LEARNER, CLIP, "가".repeat(51))))
        .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    assertThat(rateLimiter.acquiredBy).isEmpty();
    assertThat(gateway.clonedNames).isEmpty();
  }

  @Test
  void WAV_머리글이_없으면_400이다() {
    byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    byte[] rifx = VoiceTestClips.wav(64);
    rifx[3] = 'X';

    for (byte[] clip :
        new byte[][] {
          null, new byte[0], "RIFF".getBytes(StandardCharsets.US_ASCII), mp3, rifx
        }) {
      assertThat(errorOf(() -> service.cloneVoice(LEARNER, clip, null)))
          .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  // ---------- 합성 입력 ----------

  @Test
  void 문장은_앞뒤_공백을_지운_값으로_합성하고_300자까지_받는다() {
    String threeHundred = "가".repeat(300);

    assertThat(service.synthesize(LEARNER, "voice-1", "  안녕하세요  "))
        .isEqualTo(RecordingVoiceSynthesisGateway.MP3);
    service.synthesize(LEARNER, "voice-1", " " + threeHundred + " ");

    assertThat(gateway.spoken).containsExactly("voice-1/안녕하세요", "voice-1/" + threeHundred);
  }

  @Test
  void 문장이_비었거나_300자를_넘으면_400이고_사용량을_세지_않는다() {
    for (String text : new String[] {null, "", "   ", "가".repeat(301)}) {
      assertThat(errorOf(() -> service.synthesize(LEARNER, "voice-1", text)))
          .as("text=%s", text)
          .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void voiceId는_100자까지_받고_비었거나_넘으면_400이다() {
    service.synthesize(LEARNER, "v".repeat(100), "안녕");

    for (String voiceId : new String[] {null, "", "  ", "v".repeat(101)}) {
      assertThat(errorOf(() -> service.synthesize(LEARNER, voiceId, "안녕")))
          .as("voiceId=%s", voiceId)
          .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    assertThat(rateLimiter.acquiredBy).containsExactly(7L);
  }

  // ---------- 사용량·외부 실패 ----------

  @Test
  void 사용량을_넘으면_429이고_Cartesia를_부르지_않는다() {
    rateLimiter.allowing = false;

    assertThat(errorOf(() -> service.cloneVoice(LEARNER, CLIP, null)))
        .isEqualTo(ErrorCode.VOICE_RATE_LIMITED);
    assertThat(errorOf(() -> service.synthesize(LEARNER, "voice-1", "안녕")))
        .isEqualTo(ErrorCode.VOICE_RATE_LIMITED);
    assertThat(gateway.clonedNames).isEmpty();
    assertThat(gateway.spoken).isEmpty();
  }

  @Test
  void 사용량_저장소가_실패하면_503이고_Cartesia를_부르지_않는다() {
    rateLimiter.failure = new RedisConnectionFailureException("redis down");

    assertThat(errorOf(() -> service.synthesize(LEARNER, "voice-1", "안녕")))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertThat(gateway.spoken).isEmpty();
  }

  @Test
  void Cartesia_실패는_502로_바꾸고_원문을_message에_싣지_않는다() {
    gateway.failing = true;

    BusinessException clone =
        catchThrowableOfType(
            BusinessException.class, () -> service.cloneVoice(LEARNER, CLIP, null));
    BusinessException speech =
        catchThrowableOfType(
            BusinessException.class, () -> service.synthesize(LEARNER, "voice-1", "안녕"));

    assertThat(clone.getErrorCode()).isEqualTo(ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertThat(speech.getErrorCode()).isEqualTo(ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertThat(speech.getMessage()).doesNotContain("upstream-secret-detail");
    assertThat(rateLimiter.acquiredBy).containsExactly(7L, 7L);
  }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests com.itda.voice.service.VoiceSynthesisServiceTest`
Expected: FAIL — `compileTestJava` 에서 `cannot find symbol VoiceSynthesisService`

- [ ] **Step 4: 서비스 작성**

`VoiceSynthesisService.java`:

```java
package com.itda.voice.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.user.domain.UserRole;
import com.itda.voice.cartesia.VoiceSynthesisException;
import com.itda.voice.cartesia.VoiceSynthesisGateway;
import com.itda.voice.repository.VoiceRateLimiter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 목소리 복제·합성 대리 호출 (API 명세 12절).
 *
 * <p>판정 순서: 역할(403) → 키 설정(503) → 입력(400) → 사용량(429) → Cartesia(502). 입력 오류는 사용량을 세지 않고, Cartesia가 실패한
 * 요청은 센다 — 외부 호출 비용은 이미 났다.
 *
 * <p>입력 검증을 Bean Validation에 맡기지 않는다. {@code @Valid} 실패는 공통 처리기에서 422가 되지만 계약은 400이고, "앞뒤 공백 제거 후
 * 1~300자"는 애너테이션으로 표현되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceSynthesisService {

  static final String DEFAULT_VOICE_NAME = "내 목소리";
  static final int MAX_VOICE_NAME_LENGTH = 50;
  static final int MAX_VOICE_ID_LENGTH = 100;
  static final int MAX_TEXT_LENGTH = 300;

  private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] WAVE = "WAVE".getBytes(StandardCharsets.US_ASCII);

  /** {@code RIFF} (4) + 크기 (4) + {@code WAVE} (4). 이보다 짧으면 WAV가 아니다. */
  private static final int WAV_HEADER_PREFIX = 12;

  private final VoiceSynthesisGateway gateway;
  private final VoiceRateLimiter rateLimiter;

  /** @return 외부 서비스의 voice id */
  public String cloneVoice(AuthenticatedUser user, byte[] clip, String name) {
    requireLearner(user);
    requireAvailable();
    if (!isWav(clip)) {
      throw invalidInput();
    }
    String voiceName = voiceName(name);
    consumeBudget(user);
    try {
      return this.gateway.cloneVoice(clip, voiceName);
    } catch (VoiceSynthesisException e) {
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_FAILED);
    }
  }

  /** @return MP3 바이트 */
  public byte[] synthesize(AuthenticatedUser user, String voiceId, String text) {
    requireLearner(user);
    requireAvailable();
    if (voiceId == null || voiceId.isBlank() || length(voiceId) > MAX_VOICE_ID_LENGTH) {
      throw invalidInput();
    }
    String transcript = transcript(text);
    consumeBudget(user);
    try {
      return this.gateway.synthesize(voiceId, transcript);
    } catch (VoiceSynthesisException e) {
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_FAILED);
    }
  }

  private static void requireLearner(AuthenticatedUser user) {
    if (user.role() != UserRole.LEARNER) {
      throw new BusinessException(ErrorCode.LEARNER_ACCESS_REQUIRED);
    }
  }

  private void requireAvailable() {
    if (!this.gateway.isAvailable()) {
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    }
  }

  /** 저장소 장애면 호출을 막는다(fail-closed). 막지 않으면 장애 동안 키 사용량 한도가 사라진다. */
  private void consumeBudget(AuthenticatedUser user) {
    boolean allowed;
    try {
      allowed = this.rateLimiter.tryAcquire(user.userId());
    } catch (DataAccessException e) {
      log.warn("음성 사용량 저장소 오류: {}", e.getClass().getSimpleName());
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    }
    if (!allowed) {
      throw new BusinessException(ErrorCode.VOICE_RATE_LIMITED);
    }
  }

  private static boolean isWav(byte[] clip) {
    return clip != null
        && clip.length > WAV_HEADER_PREFIX
        && Arrays.equals(clip, 0, 4, RIFF, 0, 4)
        && Arrays.equals(clip, 8, 12, WAVE, 0, 4);
  }

  private static String voiceName(String name) {
    if (name == null || name.isBlank()) {
      return DEFAULT_VOICE_NAME;
    }
    String stripped = name.strip();
    if (length(stripped) > MAX_VOICE_NAME_LENGTH) {
      throw invalidInput();
    }
    return stripped;
  }

  private static String transcript(String text) {
    if (text == null) {
      throw invalidInput();
    }
    String stripped = text.strip();
    if (stripped.isEmpty() || length(stripped) > MAX_TEXT_LENGTH) {
      throw invalidInput();
    }
    return stripped;
  }

  /** 글자 수는 code point로 센다. UTF-16 길이로 세면 이모지 하나가 두 자가 된다. */
  private static int length(String value) {
    return value.codePointCount(0, value.length());
  }

  private static BusinessException invalidInput() {
    return new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
  }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests com.itda.voice.service.VoiceSynthesisServiceTest`
Expected: PASS (13 tests — 파라미터화 2건 포함)

- [ ] **Step 6: 전체 테스트·포맷·커밋**

```bash
./gradlew spotlessApply && git status --short
./gradlew test spotlessCheck
cd ../..
git add apps/backend/src/main/java/com/itda/voice/service apps/backend/src/test/java/com/itda/voice/service
git commit -m "$(cat <<'EOF'
feat(voice): 음성 합성 요청의 권한·입력·사용량 판정을 추가한다

역할, 키 설정, 입력, 사용량, Cartesia 호출 순서로 판정하고 각각
403·503·400·429·502 오류 code로 바꾼다. 사용량 저장소 장애는 503으로
끊는다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: HTTP 진입점·보호 경로·multipart 오류

**Files:**
- Create: `apps/backend/src/main/java/com/itda/voice/dto/VoiceCloneResponse.java`
- Create: `apps/backend/src/main/java/com/itda/voice/dto/VoiceSpeechRequest.java`
- Create: `apps/backend/src/main/java/com/itda/voice/controller/VoiceController.java`
- Modify: `apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java` (경로 상수 2개, matcher 2개)
- Modify: `apps/backend/src/main/java/com/itda/global/error/GlobalExceptionHandler.java` (처리기 2개)
- Modify: `apps/backend/src/main/resources/application.yml` (`spring.servlet.multipart`)
- Test: `apps/backend/src/test/java/com/itda/voice/controller/VoiceApiIntegrationTest.java`

**Interfaces:**
- Consumes: `VoiceSynthesisService.cloneVoice(AuthenticatedUser, byte[], String) : String`, `VoiceSynthesisService.synthesize(AuthenticatedUser, String, String) : byte[]` (Task 4), 테스트 대역 `RecordingVoiceSynthesisGateway`·`StubVoiceRateLimiter`·`VoiceTestClips` (Task 4), `VoiceSynthesisGateway`·`VoiceRateLimiter` 타입 (Task 2·3)
- Produces: `record VoiceCloneResponse(String voiceId)`, `record VoiceSpeechRequest(String voiceId, String text)`, `POST /voice/clones`(201), `POST /voice/speech`(200 `audio/mpeg`)
- Produces: `GlobalExceptionHandler.handleUploadTooLarge()` → 413 body 없음, `GlobalExceptionHandler.handleUnreadableMultipart(HttpServletRequest)` → 400 `INVALID_REQUEST_PARAMETER`

- [ ] **Step 1: 실패하는 HTTP 통합 테스트 작성**

`VoiceApiIntegrationTest.java`:

```java
package com.itda.voice.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.itda.global.error.ErrorCode;
import com.itda.support.web.HttpTestClient;
import com.itda.support.web.WebApiTest;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRole;
import com.itda.user.repository.UserRepository;
import com.itda.user.service.AccessTokenIssuer;
import com.itda.voice.service.RecordingVoiceSynthesisGateway;
import com.itda.voice.service.StubVoiceRateLimiter;
import com.itda.voice.service.VoiceTestClips;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;

/**
 * 음성 합성 대리 호출을 실제 HTTP로 검증한다 (API 명세 12절). 인증·역할·multipart 해석·오류 body는 Security·Tomcat·오류 처리기가 함께
 * 만들어야 해서 단위 테스트로는 드러나지 않는다. Cartesia와 Redis는 대역으로 바꿔 네트워크를 쓰지 않는다 — 실제 Cartesia 요청 모양은
 * CartesiaVoiceGatewayTest, 실제 Redis 카운터는 RedisVoiceRateLimiterRedisTest가 맡는다.
 */
@WebApiTest
@Import(VoiceApiIntegrationTest.FakeVoiceConfiguration.class)
class VoiceApiIntegrationTest {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private static final String BOUNDARY = "itda-voice-boundary";
  private static final String SPEECH_BODY = "{\"voiceId\":\"voice-1\",\"text\":\"안녕하세요\"}";
  private static final int TEN_MB = 10 * 1024 * 1024;
  private static final AtomicLong ACCOUNT_SEQUENCE = new AtomicLong();

  @LocalServerPort private int port;
  @Autowired private AccessTokenIssuer accessTokenIssuer;
  @Autowired private UserRepository userRepository;
  @Autowired private RecordingVoiceSynthesisGateway gateway;
  @Autowired private StubVoiceRateLimiter rateLimiter;

  private String learnerToken;
  private String instructorToken;

  @BeforeEach
  void setUp() {
    this.gateway.reset();
    this.rateLimiter.reset();
    this.learnerToken = tokenFor(UserRole.LEARNER);
    this.instructorToken = tokenFor(UserRole.INSTRUCTOR);
  }

  /** 매번 새 계정을 만든다. 단조 증가 수열이라 같은 실행 안에서 UNIQUE 제약에 걸리지 않는다. */
  private String tokenFor(UserRole role) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    String username = "voice" + role.name().toLowerCase() + ACCOUNT_SEQUENCE.incrementAndGet();
    User user =
        User.create(username, "argon2id$hash", role, Email.of(username + "@example.test"), now);
    user.verifyEmail(now);
    long userId = this.userRepository.save(user).getId();
    return this.accessTokenIssuer
        .issue(new AccessTokenIssuer.Claims(userId, role, 0))
        .compactToken();
  }

  private <T> HttpResponse<T> post(
      String path,
      String contentType,
      byte[] body,
      String token,
      HttpResponse.BodyHandler<T> handler) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/v1" + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    try {
      return CLIENT.send(request.build(), handler);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** clip·name이 null이면 그 파트를 넣지 않는다. */
  private HttpResponse<String> postClip(String token, byte[] clip, String name) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    if (clip != null) {
      body.writeBytes(
          ("--"
                  + BOUNDARY
                  + "\r\nContent-Disposition: form-data; name=\"clip\"; filename=\"voice-sample.wav\""
                  + "\r\nContent-Type: audio/wav\r\n\r\n")
              .getBytes(UTF_8));
      body.writeBytes(clip);
      body.writeBytes("\r\n".getBytes(UTF_8));
    }
    if (name != null) {
      body.writeBytes(
          ("--"
                  + BOUNDARY
                  + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n"
                  + name
                  + "\r\n")
              .getBytes(UTF_8));
    }
    body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(UTF_8));
    return post(
        "/voice/clones",
        "multipart/form-data; boundary=" + BOUNDARY,
        body.toByteArray(),
        token,
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postSpeech(String token, String json) {
    return post(
        "/voice/speech",
        "application/json",
        json.getBytes(UTF_8),
        token,
        HttpResponse.BodyHandlers.ofString());
  }

  /** 정확히 두 필드만 비교하므로 Cartesia 원문·내부 예외 message가 끼어들 수 없다. */
  private static void assertError(HttpResponse<String> response, ErrorCode code) {
    assertThat(response.statusCode()).isEqualTo(code.getStatus().value());
    assertThat(HttpTestClient.json(response))
        .isEqualTo(Map.of("code", code.getCode(), "message", code.getMessage()));
  }

  // ---------- 성공 ----------

  @Test
  void 학습자가_WAV를_올리면_201과_voiceId를_받고_이름이_그대로_전달된다() {
    HttpResponse<String> response =
        postClip(this.learnerToken, VoiceTestClips.wav(2048), "우리 반 목소리");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(HttpTestClient.json(response))
        .isEqualTo(Map.of("voiceId", RecordingVoiceSynthesisGateway.VOICE_ID));
    assertThat(this.gateway.clonedNames).containsExactly("우리 반 목소리");
    assertThat(this.gateway.clonedClipSizes).containsExactly(2048);
  }

  @Test
  void 정확히_10MB인_녹음은_받는다() {
    HttpResponse<String> response = postClip(this.learnerToken, VoiceTestClips.wav(TEN_MB), null);

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(this.gateway.clonedClipSizes).containsExactly(TEN_MB);
  }

  @Test
  void 학습자가_문장을_보내면_MP3_바이트를_audio_mpeg로_받는다() {
    HttpResponse<byte[]> response =
        post(
            "/voice/speech",
            "application/json",
            "{\"voiceId\":\"voice-1\",\"text\":\"  안녕하세요  \"}".getBytes(UTF_8),
            this.learnerToken,
            HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("audio/mpeg");
    assertThat(response.body()).isEqualTo(RecordingVoiceSynthesisGateway.MP3);
    assertThat(this.gateway.spoken).containsExactly("voice-1/안녕하세요");
  }

  // ---------- 인증·역할 ----------

  @Test
  void 토큰이_없으면_두_API_모두_401_UNAUTHORIZED다() {
    HttpResponse<String> clone = postClip(null, VoiceTestClips.wav(64), null);
    HttpResponse<String> speech = postSpeech(null, SPEECH_BODY);

    assertThat(clone.statusCode()).isEqualTo(401);
    assertThat(speech.statusCode()).isEqualTo(401);
    assertThat(HttpTestClient.json(speech)).containsEntry("code", "UNAUTHORIZED");
    assertThat(this.gateway.clonedNames).isEmpty();
    assertThat(this.gateway.spoken).isEmpty();
  }

  @Test
  void 강사는_두_API_모두_403_LEARNER_ACCESS_REQUIRED다() {
    assertError(
        postClip(this.instructorToken, VoiceTestClips.wav(64), null),
        ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertError(
        postSpeech(this.instructorToken, SPEECH_BODY), ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertThat(this.rateLimiter.acquiredBy).isEmpty();
  }

  // ---------- 입력 ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"voiceId\":\"voice-1\",\"text\":\"   \"}",
        "{\"voiceId\":\"\",\"text\":\"안녕\"}",
        "{\"text\":\"안녕\"}",
        "{\"voiceId\":\"voice-1\"}"
      })
  void 합성_입력이_계약을_어기면_400_INVALID_REQUEST_PARAMETER다(String body) {
    assertError(postSpeech(this.learnerToken, body), ErrorCode.INVALID_REQUEST_PARAMETER);
    assertThat(this.rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void 녹음_파일이_없거나_WAV가_아니면_400_INVALID_REQUEST_PARAMETER다() {
    assertError(postClip(this.learnerToken, null, "이름만"), ErrorCode.INVALID_REQUEST_PARAMETER);
    assertError(
        postClip(this.learnerToken, "not a wav file at all".getBytes(UTF_8), null),
        ErrorCode.INVALID_REQUEST_PARAMETER);
    assertThat(this.gateway.clonedNames).isEmpty();
  }

  @Test
  void multipart가_아닌_복제_요청은_415_UNSUPPORTED_CONTENT_TYPE이다() {
    HttpResponse<String> response =
        post(
            "/voice/clones",
            "application/json",
            "{}".getBytes(UTF_8),
            this.learnerToken,
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(415);
    assertThat(HttpTestClient.json(response)).containsEntry("code", "UNSUPPORTED_CONTENT_TYPE");
  }

  @Test
  void boundary가_없는_multipart는_500이_아니라_400_INVALID_REQUEST_PARAMETER다() {
    HttpResponse<String> response =
        post(
            "/voice/clones",
            "multipart/form-data",
            "garbage".getBytes(UTF_8),
            this.learnerToken,
            HttpResponse.BodyHandlers.ofString());

    assertError(response, ErrorCode.INVALID_REQUEST_PARAMETER);
  }

  @Test
  void 녹음_파일이_10MB를_넘으면_413을_body_없이_보낸다() {
    HttpResponse<String> response =
        postClip(this.learnerToken, VoiceTestClips.wav(TEN_MB + 1), null);

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).isEmpty();
    assertThat(this.gateway.clonedNames).isEmpty();
  }

  // ---------- 설정·사용량·외부 실패 ----------

  @Test
  void API_키가_없으면_두_API_모두_503_VOICE_SYNTHESIS_UNAVAILABLE이다() {
    this.gateway.available = false;

    assertError(
        postClip(this.learnerToken, VoiceTestClips.wav(64), null),
        ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertError(
        postSpeech(this.learnerToken, SPEECH_BODY), ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
  }

  @Test
  void 사용량을_넘으면_두_API_모두_429_VOICE_RATE_LIMITED다() {
    this.rateLimiter.allowing = false;

    assertError(
        postClip(this.learnerToken, VoiceTestClips.wav(64), null), ErrorCode.VOICE_RATE_LIMITED);
    assertError(postSpeech(this.learnerToken, SPEECH_BODY), ErrorCode.VOICE_RATE_LIMITED);
    assertThat(this.gateway.clonedNames).isEmpty();
    assertThat(this.gateway.spoken).isEmpty();
  }

  @Test
  void Cartesia가_실패하면_502이고_원문을_응답에_넣지_않는다() {
    this.gateway.failing = true;

    HttpResponse<String> clone = postClip(this.learnerToken, VoiceTestClips.wav(64), null);
    HttpResponse<String> speech = postSpeech(this.learnerToken, SPEECH_BODY);

    assertError(clone, ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertError(speech, ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertThat(clone.body() + speech.body()).doesNotContain("upstream-secret-detail");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeVoiceConfiguration {

    @Bean
    @Primary
    RecordingVoiceSynthesisGateway recordingVoiceSynthesisGateway() {
      return new RecordingVoiceSynthesisGateway();
    }

    @Bean
    @Primary
    StubVoiceRateLimiter stubVoiceRateLimiter() {
      return new StubVoiceRateLimiter();
    }
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/backend && ./gradlew test --tests com.itda.voice.controller.VoiceApiIntegrationTest`
Expected: FAIL — 컴파일은 되고(대역·서비스는 Task 4에 있다) 대부분 404 `ENDPOINT_NOT_FOUND`(컨트롤러 없음) 또는 401 대신 404로 실패한다.

- [ ] **Step 3: DTO 작성**

`VoiceCloneResponse.java`:

```java
package com.itda.voice.dto;

/** 목소리 복제 결과 (API 명세 12절). 앱이 이 id를 보관해 합성에 쓴다. */
public record VoiceCloneResponse(String voiceId) {}
```

`VoiceSpeechRequest.java`:

```java
package com.itda.voice.dto;

/**
 * 합성 요청 (API 명세 12절). 검증 애너테이션을 달지 않는다 — 길이 규칙(앞뒤 공백 제거 후 1~300자)과 400 응답은
 * VoiceSynthesisService가 맡는다. {@code @Valid} 로 검증하면 공통 처리기에서 422가 된다.
 */
public record VoiceSpeechRequest(String voiceId, String text) {}
```

- [ ] **Step 4: 컨트롤러 작성**

`VoiceController.java`:

```java
package com.itda.voice.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.voice.dto.VoiceCloneResponse;
import com.itda.voice.dto.VoiceSpeechRequest;
import com.itda.voice.service.VoiceSynthesisService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 목소리 복제·합성 대리 호출 (API 명세 12절).
 *
 * <p>{@code clip} 을 {@code required=false} 로 받는다. 필수로 두면 누락이 {@code MissingServletRequestPartException}
 * 이 되는데 공통 처리기에 그 처리가 없어 500이 된다. 누락·빈 파일·WAV 아님은 서비스가 한꺼번에 400으로 판정한다.
 */
@RestController
@RequiredArgsConstructor
public class VoiceController {

  private static final MediaType AUDIO_MPEG = MediaType.parseMediaType("audio/mpeg");

  private final VoiceSynthesisService voiceSynthesisService;

  @PostMapping(path = "/voice/clones", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<VoiceCloneResponse> cloneVoice(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestPart(name = "clip", required = false) MultipartFile clip,
      @RequestParam(name = "name", required = false) String name)
      throws IOException {
    byte[] clipBytes = clip == null ? new byte[0] : clip.getBytes();
    String voiceId = this.voiceSynthesisService.cloneVoice(user, clipBytes, name);
    return ResponseEntity.status(HttpStatus.CREATED).body(new VoiceCloneResponse(voiceId));
  }

  @PostMapping("/voice/speech")
  public ResponseEntity<byte[]> synthesize(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody VoiceSpeechRequest request) {
    byte[] audio =
        this.voiceSynthesisService.synthesize(user, request.voiceId(), request.text());
    return ResponseEntity.ok().contentType(AUDIO_MPEG).body(audio);
  }
}
```

- [ ] **Step 5: 보호 경로 추가**

`SecurityConfig.java` — 다음 줄

```java
  private static final String LECTURE_PARTICIPATIONS_PATH = "/lecture-participations";
```

바로 뒤에 추가:

```java

  /** 음성 합성 대리 호출 (API 명세 12절). 학습자 역할로 판정하고 사용량을 사용자별로 세므로 인증이 필요하다. */
  private static final String VOICE_CLONES_PATH = "/voice/clones";

  private static final String VOICE_SPEECH_PATH = "/voice/speech";
```

matcher 목록의 마지막 두 줄

```java
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LECTURE_PARTICIPATIONS_PATH)))
```

을 다음으로 바꾼다:

```java
                PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, LECTURE_PARTICIPATIONS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, VOICE_CLONES_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, VOICE_SPEECH_PATH)))
```

- [ ] **Step 6: multipart 오류 처리기와 한도**

`GlobalExceptionHandler.java` import 블록에 추가:

```java
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
```

`handleContainerParameter` 메서드 바로 뒤에 추가:

```java
  /**
   * multipart 한도(파일 10MB, API 명세 12절) 초과. 413은 승인된 공통 code가 없으므로 상태만 유지하고 body를 비운다 (api.md 「오류 상태
   * 보존과 body 생략」). 처리하지 않으면 마지막 처리기가 500으로 덮는다.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> handleUploadTooLarge() {
    return ErrorHttpResponseFactory.withoutBody(HttpStatus.CONTENT_TOO_LARGE);
  }

  /** multipart 본문을 해석하지 못했다(boundary 없음·깨진 파트). 요청 파라미터 파싱 실패와 같은 400이다. */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorResponse> handleUnreadableMultipart(HttpServletRequest request) {
    return ErrorHttpResponseFactory.create(request, ErrorCode.INVALID_REQUEST_PARAMETER);
  }
```

(`MaxUploadSizeExceededException` 은 `MultipartException` 의 하위 타입이다. Spring은 더 가까운 타입의 처리기를 고르므로 413이 먼저 걸린다.)

`application.yml` — 맨 위 `spring:` 블록의 다음 두 줄

```yaml
      connect-timeout: 1s
      timeout: 1s
```

바로 뒤(`  jpa:` 앞)에 추가:

```yaml
  servlet:
    multipart:
      # 목소리 복제 녹음(WAV) 한도 (API 명세 12절). Boot 기본값(1MB)으로는 계약을 지킬 수 없다.
      # 요청 전체는 파일 + name 파트·경계 문자열 여유분이다. 넘으면 413을 body 없이 보낸다 (GlobalExceptionHandler).
      max-file-size: 10MB
      max-request-size: 11MB
```

- [ ] **Step 7: 통과 확인**

Run: `./gradlew test --tests com.itda.voice.controller.VoiceApiIntegrationTest`
Expected: PASS (16 tests — 파라미터화 4건 포함)

실패하면 이렇게 좁힌다:
- `boundary가_없는_multipart...` 가 500이면: Step 6의 `MultipartException` 처리기가 빠졌거나 import가 틀렸다. 400이지만 code가 다르면 응답 body를 출력해 본다
- `10MB를_넘으면_413...` 이 연결 오류(`IOException: connection reset`)로 흔들리면 Tomcat이 남은 본문을 버리기 전에 연결을 닫은 것이다 — 이 테스트의 초과분은 1바이트 + 닫는 경계뿐이라 정상이라면 일어나지 않는다. 일어나면 멈추고 보고한다(한도를 바꾸지 않는다)
- 401 테스트가 404면: Step 5의 matcher가 빠졌다

- [ ] **Step 8: 전체 테스트·포맷·커밋**

```bash
./gradlew spotlessApply && git status --short
./gradlew test spotlessCheck
cd ../..
git add apps/backend/src/main/java/com/itda/voice/dto apps/backend/src/main/java/com/itda/voice/controller apps/backend/src/main/java/com/itda/global/security/SecurityConfig.java apps/backend/src/main/java/com/itda/global/error/GlobalExceptionHandler.java apps/backend/src/main/resources/application.yml apps/backend/src/test/java/com/itda/voice/controller
git commit -m "$(cat <<'EOF'
feat(voice): 목소리 복제·합성 API를 연다

POST /voice/clones(multipart WAV → voiceId)와 POST /voice/speech
(문장 → audio/mpeg)를 학습자 보호 경로에 추가한다. multipart 한도를
10MB로 올리고 한도 초과는 413, 해석 실패는 400으로 보낸다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 운영 프로필 Redis 접속

**Files:**
- Modify: `apps/backend/src/main/resources/application.yml` (local 프로필 주석 한 줄, prod 프로필 `spring.data.redis`)

**Interfaces:**
- Consumes: 설계 §3.2 `.env.config` 키 `REDIS_HOST=redis`, `REDIS_PORT=6379`(infra 계획이 compose·예시 파일에 반영)
- Produces: prod 프로필에서 `spring.data.redis.host=${REDIS_HOST}`, `spring.data.redis.port=${REDIS_PORT:6379}`

설정만 바꾸는 Task라 새 테스트 대신 diff 확인과 전체 테스트로 검증한다(prod 프로필은 `DB_URL` 등 운영 값이 있어야 떠서 테스트에서 띄우지 않는다).

- [ ] **Step 1: local 프로필의 낡은 주석 교체**

기존:

```yaml
    # 컨테이너에서 실행하면 REDIS_HOST=redis, 호스트에서 bootRun하면 기본값 localhost:6379를 쓴다.
    # 운영 Redis 연결은 배포 작업에서 prod 프로필에 별도로 추가한다.
```

새:

```yaml
    # 컨테이너에서 실행하면 REDIS_HOST=redis, 호스트에서 bootRun하면 기본값 localhost:6379를 쓴다.
    # 운영은 prod 프로필이 같은 키로 운영 compose의 redis 서비스(itda-redis)에 붙는다.
```

- [ ] **Step 2: prod 프로필에 Redis 추가**

prod 프로필 블록의 마지막

```yaml
  jpa:
    hibernate:
      # 운영에서는 스키마를 자동 변경하지 않고 엔티티와 일치하는지만 검증한다.
      ddl-auto: validate
```

바로 뒤(파일 끝)에 추가:

```yaml
  data:
    # 운영 compose의 redis 서비스(itda-redis, 설계 2026-09-27-deploy-rebuild §3.1). host에 기본값을 두지 않는다 —
    # 값이 빠진 채 localhost로 조용히 붙으면 인증 갱신·음성 사용량이 모두 연결 실패로 끝나 원인을 찾기 어렵다.
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
```

- [ ] **Step 3: diff 확인과 전체 테스트**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-api
git diff -- apps/backend/src/main/resources/application.yml   # 위 두 곳만 바뀌었는지
cd apps/backend && ./gradlew test spotlessCheck
```

Expected: diff는 주석 한 줄 교체 + prod 블록 6줄 추가뿐. BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**

```bash
cd ../..
git add apps/backend/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
chore(infra): 운영 프로필이 compose의 Redis에 붙게 한다

prod 프로필에 spring.data.redis.host·port를 REDIS_HOST·REDIS_PORT로
읽게 한다. 지금까지 prod에는 Redis 접속 설정이 없어 localhost로 붙었다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 최종 검증

**Files:** 없음

- [ ] **Step 1: 전체 테스트와 포맷**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-api/apps/backend
./gradlew test spotlessCheck
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: CI와 같은 Redis 포함 검증**

```bash
cd C:/Users/SSAFY/Desktop/wt-deploy-api
docker build --target redis-test apps/backend
```

Expected: 성공. 이 스테이지는 실제 Redis 8.2.9를 띄우고 `spotlessCheck test` 를 돌린 뒤 `TEST-*RedisTest.xml` 이 모두 `skipped="0"` 인지 확인한다 — `RedisVoiceRateLimiterRedisTest` 가 건너뛰어지면 여기서 실패한다.

`docker build --target test apps/backend` 는 `mariadb-test` 스테이지의 `UserWithdrawal` 계열 19건(dev 커밋 `18846616` 에서 넘어온 기존 실패) 때문에 실패한다. 이 계획의 범위 밖이므로 실행하더라도 실패 목록이 그 19건뿐인지만 확인하고 고치지 않는다.

- [ ] **Step 3: 범위 확인**

```bash
git diff --stat chore/deploy-rebuild...HEAD
git log --oneline chore/deploy-rebuild..HEAD
```

Expected: 바뀐 파일이 `apps/backend/**` 와 `docs/product/api-spec.md` 뿐이다. `.env*`·`deploy/`·`front/`·`docs/infra/` 가 없어야 한다. 커밋 6개(Task 1~6).

- [ ] **Step 4: 비밀값 누출 점검**

```bash
git diff chore/deploy-rebuild...HEAD | grep -nE "sk_car|CARTESIA_API_KEY=[^$}]" || echo "no key values"
```

Expected: `no key values` (테스트의 `test-cartesia-key` 는 가짜 값이다).

## 완료 확인 (보고 전)

- [ ] `./gradlew test spotlessCheck` 통과, `docker build --target redis-test apps/backend` 통과를 확인했다
- [ ] 키가 비어 있어도 컨텍스트가 뜬다(`ItdaApplicationTests` 포함 전체 테스트가 `CARTESIA_API_KEY` 없이 통과)
- [ ] 두 경로의 401·403·400·413·415·429·502·503 응답을 `VoiceApiIntegrationTest` 로, Cartesia 요청 모양(헤더·multipart 파트·`ttsRequestBody`)과 오류 본문 비누출을 `CartesiaVoiceGatewayTest` 로, 실제 Redis 키·TTL·30회 경계를 `RedisVoiceRateLimiterRedisTest` 로 확인했다
- [ ] 최종 보고에 사람이 할 일을 적는다: 저장소 루트 `.env.example` 에 `CARTESIA_API_KEY=` 추가(에이전트는 `.env*` 를 고치지 않는다), Jenkins Secret file `itda-env-config` 에 `CARTESIA_API_KEY` 값 추가(infra 계획의 `deploy/.env.config.example` 키 목록과 함께)

## 알려진 한계·다른 계획에 넘길 것

- **인증 헤더**: 교차 계약의 `X-API-Key` 대신 앱이 검증한 `Authorization: Bearer` 를 쓴다(Global Constraints). 계약 문서를 고칠지는 사람이 정한다
- **nginx 본문 한도**: infra 계약은 `/api/` 에 `client_max_body_size 10m` 이다. 녹음 파일이 10MB에 아주 가까우면 multipart 여유분 때문에 nginx가 먼저 413을 낸다(backend까지 오지 않는다). 앱 녹음 길이로는 닿지 않지만(16bit mono 44.1kHz 기준 10MB ≈ 2분), 정확히 맞추려면 nginx를 `11m` 으로 두는 것을 infra 계획에 제안한다
- **앱 요청 제한 시간**: backend의 Cartesia 대기(복제 60초·합성 20초)보다 앱→backend 제한 시간이 길어야 한다. 지금 앱의 복제 120초·합성 20초 중 합성 20초는 backend 20초와 같아 경계에서 앱이 먼저 끊을 수 있다 — front 계획에서 합성을 25초 이상으로 두는 것을 제안한다
- **사용량 창**: 계약 키가 epoch minute이라 "1분 30회"는 분 단위 고정 창이다. 분 경계 양쪽으로 최대 60회가 몰릴 수 있다(계약대로 둔다)
- **운영 Redis prefix**: `itda.security.auth-redis.key-prefix: ${AUTH_REDIS_KEY_PREFIX}` 에는 기본값이 없다. 설계 §3.2는 "선택"으로 적었지만 `AuthRedisProperties` 가 미해결 플레이스홀더와 허용값(`local`·`test`·`prod`) 밖·프로필과 어긋난 값을 기동 단계에서 거부한다 — 운영은 `AUTH_REDIS_KEY_PREFIX=prod` 가 **필수**다. infra 계획의 `.env.config` 예시에 반드시 넣도록 알린다
- Redis 장애 시 503 `VOICE_SYNTHESIS_UNAVAILABLE`(fail-closed)은 계약이 명시하지 않은 확장이다. api-spec §12 표에 적었다
