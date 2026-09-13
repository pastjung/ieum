# backend — Spring Boot (Java 25)

## 스택

- Java 25 (Gradle toolchain이 자동으로 JDK 25를 내려받음)
- Spring Boot 4.0.x (Boot 4부터 web 스타터 이름이 `spring-boot-starter-webmvc`)
- Spring Data JPA — local·prod: MariaDB 11.8.9, test: H2(MySQL 모드)
- Spring Data Redis — 인증 토큰 상태 저장 (Redis 8.2.9, [ERD 3.1.1](../../docs/product/erd.md#redis-auth))
- Lombok, Bean Validation

## 실행

```bash
# 전체 스택(웹·백엔드·AI·MariaDB·Redis) — 권장
docker compose up --build --watch          # 루트에서 실행. 상세: docs/infra/docker.md

# 백엔드만, 호스트에서
docker compose up -d db redis              # 저장소 두 개를 먼저 띄우고
./gradlew bootRun                          # 기본 local 프로필 (localhost:3306, localhost:6379)
```

- spring-boot-devtools 적용: 코드 재컴파일 시 애플리케이션 자동 재시작 (IntelliJ에서는 Build Project(Ctrl+F9) 시 반영)
- Health check: `GET http://localhost:8080/api/v1/health`

> gradlew 래퍼가 없다면 최초 1회 `gradle wrapper --gradle-version 9.2` 실행 (IntelliJ로 열면 자동 처리).

## 프로필과 저장소

| 프로필 | DB | `ddl-auto` | Redis | 용도 |
| --- | --- | --- | --- | --- |
| `local` (기본) | MariaDB 11.8.9 (compose `db` 서비스) | `update` | compose `redis` 서비스 | 개발 |
| `test` | H2 인메모리 (MySQL 모드) | `create-drop` | 실제 Redis (아래 참고) | `./gradlew test`, `docker build --target test` |
| `prod` | MariaDB (RDS) | `validate` | 배포 작업에서 추가 예정 | 배포 |

`test`가 인메모리인 이유: MR verify 파이프라인의 `docker build --target test`는 DB 컨테이너도 네트워크도 없이 실행된다.
테스트 프로필은 `src/test/resources/application.properties`가 고정한다.

**스키마 원본은 엔티티다.** Flyway는 아직 도입하지 않았으므로 ERD의 CHECK 제약·`ON DELETE RESTRICT`·`DEFAULT
CURRENT_TIMESTAMP(6)`는 DB에 반영되지 않는다. 그 규칙은 엔티티 생성자·도메인 메서드의 불변식이 대신 보장하며, 각 엔티티 주석에 해당 ERD 절을 적어 두었다.
설계 근거: [docs/superpowers/specs/2026-09-09-backend-ddd-entities-design.md](../../docs/superpowers/specs/2026-09-09-backend-ddd-entities-design.md)

환경변수 키는 루트 `.env.example`을 따른다. 새 키를 추가하면 그 파일도 함께 갱신한다.

**`DB_PASSWORD`는 필수다.** 저장소에 자격증명을 남기지 않기 위해 `docker-compose.yml`과 `application.yml`
어디에도 비밀번호 기본값을 두지 않았다. 루트에서 `cp .env.example .env` 후 값을 채운다.

`.env`는 두 경로 모두에서 읽힌다 — compose는 `env_file`로, 호스트 `bootRun`은 `local` 프로필의
`spring.config.import`로 저장소 루트를 읽는다(`apps/ai`가 pydantic `env_file`로 하는 것과 같은 규약).
OS 환경변수가 이 파일보다 우선하므로 compose가 주입한 값이 항상 이긴다.

값이 없으면 `db` 서비스는 기동 단계에서 파일 누락을 알리며 실패한다. 다만 호스트 `bootRun`은
Spring이 미해결 플레이스홀더에 예외를 던지지 않아 `Access denied`로 끝난다 — 설정 누락이라고
알려 주지 않으니 이 메시지를 보면 `.env`를 먼저 확인한다.

Redis에는 비밀번호가 없다 (`REDIS_HOST`·`REDIS_PORT`만 있고 기본값이 있다). 로컬 컨테이너가
`127.0.0.1`에만 바인딩되기 때문이며, 운영 Redis의 인증은 배포 작업에서 `.env.config`로 다룬다.

### Redis 통합 테스트

인증 상태 저장소에는 인메모리 대체재가 없다. Lua 스크립트의 원자성, `EVALSHA` 실패 후 복구, TTL·ZSET 갱신은
실제 Redis에서만 증명된다. 그래서 `@RequiresRealRedis`가 붙은 테스트는 진짜 서버에 붙는다.

```bash
docker compose up -d redis    # 로컬에서 함께 돌리려면
./gradlew test
```

- Redis가 없으면 그 테스트만 건너뛴다 (나머지는 그대로 실행된다).
- `docker build --target test`는 `redis:8.2.9-alpine`에서 바이너리를 복사해 같은 컨테이너 안에 8.2.9를 띄우고
  `ITDA_REQUIRE_REAL_REDIS=true`로 실행한다. **이 환경에서는 건너뛰기가 실패로 처리된다** — 버전 확인·PING·
  연결 중 하나라도 어긋나면 빌드가 멈춘다. CI에 Docker 소켓이 없어 별도 컨테이너를 띄울 수 없기 때문에 택한 방식이다.
- 테스트는 `FLUSHDB`·`FLUSHALL`을 쓰지 않는다. 실행마다 새 식별자를 만들고 자기가 만든 키만 지운다.
- 운영 이미지(builder·runtime 스테이지)에는 Redis 바이너리가 들어가지 않는다.

## DDD 패키지 구조

현재 backend에는 15개 엔티티 테이블이 구현되어 있고 5개 바운디드 컨텍스트로 나눴다 ([docs/product/erd.md](../../docs/product/erd.md)). 제품 ERD에는 후속 구현 대상인 `mail_outbox`가 더해져 총 16개이며, 구현 클래스명과 패키지는 이 문서에서 확정하지 않는다.

```
com.itda
├── global/                  # 공통
│   ├── config/, error/, response/
│   ├── security/            # [현재] Access Token 공통 인증 기반 — SecurityFilterChain, Bearer 추출,
│   │                        #        인증 필터, 401 진입점. [예정] 역할 기반 인가
│   └── braille/             # BraillePattern — 6비트 셀 shared kernel
├── user/                    # users — 계정·인증. 아래 "계정·인증 목표 구조" 참고
│   ├── domain/              # [현재] User(애그리거트), Email(VO), UserRole, UserStatus, UserRepository(인터페이스)
│   ├── application/         # [일부 현재] 비밀번호 정책 유스케이스, Access Token 인증 상태 판정,
│                            #        port/out (PasswordHasher·CompromisedPasswordChecker·
│                            #        AccessTokenIssuer·AccessTokenVerifier·RefreshTokenIssuer·
│                            #        RefreshTokenVerifier·RefreshTokenStore)
│                            # [예정] action token·메일 outbound port와 나머지 유스케이스
│   ├── presentation/        # [예정] HTTP DTO·상태 코드·쿠키/body 변환
│   └── infrastructure/      # [현재] UserRepositoryImpl(JPA 어댑터), UserJpaRepository,
│                            #        Argon2idPasswordHasher, HibpRangePasswordChecker,
│                            #        NimbusAccessTokenCodec, AccessKeyRing,
│                            #        NimbusRefreshTokenCodec, RefreshKeyRing,
│                            #        LuaRefreshTokenStore, AuthRedisKeys, 설정 properties·구성
│                            # [예정] action token·메일 adapter
├── lecture/domain/          # Lecture(애그리거트), LectureProblem, Participant, ParticipantId, LectureStatus
├── curriculum/domain/       # CurriculumStage, CurriculumProblem, CurriculumProblemId
├── problem/domain/          # Problem(애그리거트 + ProblemCell), LearningSkill, ProblemSkill, enum 3개
└── learning/domain/         # LearningSession(애그리거트), ProblemAttempt(애그리거트 + AttemptCell·AttemptSkillResult),
                             # UserSkillMemory, AssignedProblem, CellSubmission, enum 4개
```

현재 `user/`에는 `domain`·`application`(비밀번호 정책 유스케이스와 `port/out`)·`infrastructure`가 있다.
`presentation/`과 `global/security/`는 아직 존재하지 않으며, 후속 계정 API 구현 이슈에서 추가할 목표 구조다.
비밀번호 정책·Argon2id·유출 조회 경로가 `domain`→`application`→`port/out`→`infrastructure` 방향의 예시다.

### 규칙

1. `domain`은 Spring/JPA 외 어떤 계층에도 의존하지 않는다 (JPA 애너테이션은 실용적 타협).
2. Repository **인터페이스는 domain에**, 구현체는 infrastructure에 둔다 (의존성 역전).
3. 엔티티에 setter를 만들지 않는다. 상태 변경은 의미 있는 도메인 메서드로.
4. presentation의 Request/Response DTO와 application의 Command/Info를 분리한다.
5. 시각은 엔티티가 스스로 읽지 않고 인자로 받는다. ERD가 서버 영속 시각을 UTC DB 시각으로 못박고 자동 `ON UPDATE`를 금지하므로,
   한 트랜잭션의 여러 시각이 어긋나면 안 되고 테스트도 결정적이어야 한다.

### 계정·인증 목표 구조

새 최상위 `auth` 패키지를 만들지 않고 기존 `com.itda.user` 안에서 4계층으로 구현한다.
`[예정]` 계층은 후속 계정 API 구현 이슈에서 추가하며, 유스케이스·DTO·port·adapter의 클래스명은 아직 계약이 아니다.

| 계층 | 담당 | 담당하지 않음 |
| --- | --- | --- |
| `user/domain` [현재] | 계정 상태와 핵심 불변식(상태 전이·정규화), `UserRepository` 포트 | 트랜잭션 경계, 토큰 발급, HTTP, 외부 port 직접 호출 |
| `user/application` [일부 현재] | 유스케이스, 트랜잭션 경계, outbound port 호출 조정 | HTTP 상태 코드, 쿠키, 플랫폼 분기 |
| `user/presentation` [예정] | HTTP DTO, 상태 코드, 쿠키↔body 변환 | 비즈니스 규칙, 트랜잭션 |
| `user/infrastructure` [일부 현재] | JPA, Argon2id, JWT, Redis, 메일 adapter | 유스케이스 순서, 정책 판단 |
| `global/security` [일부 현재] | Access Token 공통 인증(Bearer 추출·SecurityContext·401 진입점) | 로그인·회원가입·Refresh 검증, 사용자 상태 업무 판정 |

**Port 배치.** `UserRepository`는 위 규칙 2번대로 `user/domain`에 둔다 — 도메인 애그리거트를 되살리는 수단이라
도메인 언어의 일부다. JWT·Redis·메일·비밀번호 해시처럼 바깥에 요청하는 기능의 outbound port는
`user/application/port/out`에 두고 기술 구현은 `user/infrastructure`에 둔다.
**`domain`은 이 port들을 직접 호출하지 않는다** — 호출 순서와 트랜잭션 경계를 정하는 것은 `application`이다.

**인증 경계.** `global/security`는 Access Token 기반 보호 API의 공통 인증과 역할 인가를 담당한다.
로그인·토큰 갱신·이메일 인증·비밀번호 복구의 자격 증명 검증은 각 `application` 유스케이스가 담당한다.
Access Token 검증 port와 사용자 인증 상태 판정(존재·`status`·이메일 인증·`auth_version`)은 `user/application`에 있고,
`global/security`는 그 결과를 `SecurityContext`와 HTTP 응답으로 옮기기만 한다.

**운영 보호 endpoint는 아직 없다.** 운영 `SecurityFilterChain`은 `anyRequest().permitAll()`이며 Access 인증 필터를
운영 경로에 연결하지 않는다. 지금 전체를 `authenticated()`로 막으면 존재하지 않는 경로가 `404 ENDPOINT_NOT_FOUND` 대신
401이 되어 [API 컨벤션](../../docs/conventions/code/api.md)의 공통 오류 계약이 깨지기 때문이다.
**보호 matcher와 `AccessTokenAuthenticationFilter` 등록은 각 업무 API 구현 이슈가 자기 경로에 대해 명시적으로 추가한다.**

**Access JWT 설정.** 서명 key와 issuer는 환경변수 `AUTH_JWT_ISSUER`·`AUTH_ACCESS_ACTIVE_KID`·
`AUTH_ACCESS_ACTIVE_KEY`·`AUTH_ACCESS_RETIRED_KEYS`로 주입한다(마지막 하나는 선택). 앞의 세 값은 기본값이 없어
빠지면 기동에 실패한다. **실제 key 값은 저장소에 기록하지 않는다** — `.env.example`에는 이름과 형식만 둔다.

**Refresh JWT와 Redis 상태 설정.** Refresh는 별도 ring을 쓴다 — `AUTH_REFRESH_ACTIVE_KID`·
`AUTH_REFRESH_ACTIVE_KEY`(필수)·`AUTH_REFRESH_RETIRED_KEYS`(선택). `issuer`는 Access와 같은 `AUTH_JWT_ISSUER`를
공유하므로 따로 두지 않는다. `kid`는 Access ring과 겹치면 기동에 실패한다 — 겹치면 어느 ring의 key로 검증할지
결정할 수 없기 때문이다. 인증 상태 키의 환경 이름은 `AUTH_REDIS_KEY_PREFIX`(`local`·`test`·`prod`)로 주입하며, **실행 프로필과 다르면 기동에 실패한다**
(issuer 검증과 같은 규칙). DB index로 환경을 나누지 않는 이유는 Cluster에서 쓸 수 없고, 잘못된 설정이 조용히 다른 환경의
데이터를 읽게 만들기 때문이다.

**계정 작업 토큰(action token) 설정.** 이메일 인증과 비밀번호 재설정에 쓰는 단발성 토큰이다. Access·Refresh와 구분되는
세 번째 ring을 쓴다 — `AUTH_ACTION_ACTIVE_KID`·`AUTH_ACTION_ACTIVE_KEY`(필수)·`AUTH_ACTION_RETIRED_KEYS`(선택).
`issuer`는 Access·Refresh와 같은 `AUTH_JWT_ISSUER`를 공유한다. `kid`는 **세 ring 사이에서 모두 유일해야 하며** 겹치면
기동에 실패한다.

발급은 `action-current`를 읽어 그 값을 Lua에 기대값으로 함께 넘기는 **compare-and-set**이다. 스크립트는 쓰기 전에 `action-current`가
그대로인지 확인하고, 달라졌으면 아무것도 바꾸지 않고 adapter에만 보이는 경쟁 결과를 돌려준다. adapter는 최신 값을 다시 읽어 최대 3회까지 재시도하고,
그래도 밀리면 성공으로 단정하지 않고 `UNAVAILABLE`로 끝낸다. 이렇게 하지 않으면 동시 발급에서 `action-current`가 가리키지 않는데 상태만
`ACTIVE`인 토큰이 남는다. 새 `jti`가 이미 있다는 `ALREADY_EXISTS`는 성격이 다르다 — application이 **새 토큰을 다시 발급해** 최대 3회
시도한다(API 명세 3.6.1절).

두 용도는 key ring을 공유하되 **발급기와 검증기는 용도별로 분리한다**(`EmailVerificationTokenIssuer`·
`EmailVerificationTokenVerifier`·`PasswordResetTokenIssuer`·`PasswordResetTokenVerifier`). 검증 시점에 토큰 자신의
`typ`·`purpose`를 읽어 검증기나 ring을 고르는 경로는 만들지 않는다 — 그런 구조는 공격자가 고른 값이 검증 규칙을 고르게
한다. 각 검증기는 자기 기대값을 생성 시점에 고정하고 그와 다른 토큰은 전부 거부한다. 이메일 인증 24시간,
비밀번호 재설정 30분이며 상태(1회 사용·무효화)는 Redis에 둔다. **action token용 DB 테이블은 만들지 않는다.**

**메일 발송 작업(`mail_outbox`)과 worker.** 가입·재설정 요청은 같은 트랜잭션에서 `mail_outbox`에 PENDING 행 하나를
남기고(`MailOutboxEnqueueService`), 별도의 `MailDispatchWorker`가 이를 선점해 action token을 발급하고 `MailProvider`
port로 넘긴다. 여러 worker가 같은 행을 가져가지 않도록 선점·재시도·종결을 모두 **조건부 UPDATE 한 번**으로 쓰고 갱신된
행 수로 경쟁 결과를 판정한다. 시도 상한 6회, backoff 30초·1분·2분·4분·8분(내부 상한 10분, 지터 ±20%), 가시성 만료 5분,
종결 행 보존 7일은 ERD 3.5절 값 그대로다. 제공자 호출 결과는 API 명세 3.8절의 5종(`ACCEPTED`·`REJECTED`·
`AUTH_CONFIG_ERROR`·`RETRYABLE`·`UNCERTAIN`)으로 닫아 두고, `mail_outbox.last_error_kind`는 그와 별개의 열린
문자열이다 — 제공자 원문을 넣지 않고 내부 분류(`MAX_ATTEMPTS_EXCEEDED` 등)만 기록한다. 결과 다섯 종과 함께 **제공자가 지시한 절대 재시도 시각**을
선택적으로 받는다(`MailSendOutcome`). 그 시각이 있으면 내부 backoff 상한으로 앞당기지 않고 그대로 쓰며, 기한 이상이면 `DELIVERY_DEADLINE_EXCEEDED`로
종결한다. 요청 식별자는 batch가 아니라 **처리 시도마다** 새로 만들고 끝나면 이전 값으로 되돌린다. 한 작업에서 예상하지 못한 예외가 나도 batch의 뒤 작업은
계속 처리하며, 그 작업은 가시성 만료 뒤 다시 선점된다. 예외 message는 로그에 남기지 않는다 — 제공자 client나 JDBC 예외에는 수신 주소·토큰·SQL이
들어갈 수 있다.

ERD 3.5절의 CHECK 네 개(`attempt_count >= 0`, `delivery_deadline_at > created_at`, PENDING·종결 상태의 컬럼 조합)는
`@CheckConstraint`로 스키마에 만든다. 상태 전이가 엔티티를 거치지 않는 조건부 UPDATE라 Java 불변식만으로는 부족하기 때문이다. 다만 `ddl-auto: validate`는
테이블·컬럼·타입까지만 보므로 **운영 스키마에 이 제약이 있다는 근거가 되지 않는다** — 배포 시 `information_schema.CHECK_CONSTRAINTS`로 따로
확인한다.

**실제 메일 제공자 adapter는 아직 없다.** 운영 코드에는 발송 성공을 가장하는 no-op adapter를 두지 않는다.
`MailDispatchConfiguration`은 `MailOutboxEnqueueService`와 설정만 등록하고 **`MailDispatchWorker` 빈은 만들지 않는다** —
`@ConditionalOnBean`으로 "있으면 만든다"를 쓰면 판정이 빈 등록 순서에 좌우돼, 후속 이슈에서 provider를 추가했는데 worker가
조용히 빠진 채 기동이 성공할 수 있기 때문이다(요청 API는 계속 202를 반환하고 메일만 나가지 않는 상태). 그래서 provider adapter가
생기는 후속 이슈에서 **provider와 worker를 같은 `@Configuration`에 함께** 조립한다. 지금 worker를 쓰는 곳은 stub provider와
함께 직접 조립하는 테스트뿐이며 `@EnableScheduling`도 켜지 않는다. 제공자 선택·자격 증명·실제 링크와 본문 template·scheduler
활성화는 후속 이슈에서 다룬다. `PROVIDER_ACCEPTED`는 **제공자가 접수했다**는 뜻이며 수신함 도달을 뜻하지 않는다.
worker가 제공자 호출 뒤 DB 갱신에 실패하면 같은 메일이 다시 나갈 수 있다 — 이 구조는 at-least-once이고 exactly-once가
아니다.

**`mail_outbox` 배포 경계.** `prod`는 `ddl-auto: validate`라서 이 테이블이 운영 스키마에 먼저 있어야 기동한다.
`local`(`update`)·`test`(`create-drop`)는 자동 반영된다.

**`users.auth_version` 배포 경계.** `prod`는 `ddl-auto: validate`라서 이 컬럼이 운영 스키마에 먼저 있어야 기동한다.
`local`(`update`)·`test`(`create-drop`)는 자동 반영된다.

**플랫폼 경계.** `application`은 플랫폼 중립적인 인증 결과를 반환하고, `presentation`이 WEB은 HttpOnly 쿠키로
MOBILE은 응답 body로 변환한다. WEB/MOBILE별 Controller를 복제하지 않는다.
JWT 발급과 Redis 저장 infrastructure는 WEB/MOBILE 공통이다.

**클라이언트 책임.** 웹 쿠키 저장소와 모바일 OS 보안 저장소(Android Keystore·iOS Keychain)는
서버 infrastructure가 아니라 클라이언트 영역이다. 서버 `presentation`은 전달 방식만 결정하며
모바일 Keychain·Keystore에 직접 저장하지 않는다.

### 연관관계 매핑 기준

| 관계 | 매핑 |
| --- | --- |
| 같은 애그리거트 자식 | 자식에 `@ManyToOne(LAZY)`, 루트에 `@OneToMany(cascade = ALL, orphanRemoval)` |
| 다른 애그리거트·컨텍스트 | `Long` FK 필드 (`userId`, `problemId`, `skillId` …) |
| 복합 PK 연결 테이블 | `@EmbeddedId` + Long 2개, 연관 객체 없음 |

cascade를 쓴 3곳(`Problem→ProblemCell`, `ProblemAttempt→AttemptCell`, `ProblemAttempt→AttemptSkillResult`)은
ERD가 "전체 검증 후 한 트랜잭션으로 저장, 실패 시 일부도 저장하지 않음"을 명시한 위치와 일치한다.

`lectures.current_lecture_problem_id`는 같은 컨텍스트지만 `Long`이다 — `lectures ↔ lecture_problems`가 순환 FK라
양방향 객체 참조로는 insert 순서를 정할 수 없다.

## 검증

```bash
./gradlew spotlessApply test          # 포맷 + 테스트
docker build --target test .          # CI(MR verify)와 동일한 검증
```
