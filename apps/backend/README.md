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
| `test` | H2 인메모리 (MySQL 모드) | `create-drop` | 없음 | `./gradlew test`, `docker build --target test` |
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

### Redis 연결 확인 (임시)

`local` 프로필에서만 도는 `RedisConnectionCheck`가 기동 직후 PING을 보내고 `Redis 연결 확인: PONG`을 로그에 남긴다.
Lettuce는 지연 연결이라 host·port가 틀려도 애플리케이션이 오류 없이 기동하는데, Redis에 명령을 보내는 코드가 아직
하나도 없어 잘못된 설정을 알아챌 방법이 없기 때문이다 (MariaDB는 `ddl-auto`가 기동 중 연결해 이 문제가 없다).

**인증 토큰 저장소(ERD 3.1.1)를 구현하면 이 클래스를 삭제한다** — 그 통합 테스트가 같은 역할을 하기 때문이다.

## DDD 패키지 구조

현재 backend에는 15개 엔티티 테이블이 구현되어 있고 5개 바운디드 컨텍스트로 나눴다 ([docs/product/erd.md](../../docs/product/erd.md)). 제품 ERD에는 후속 구현 대상인 `mail_outbox`가 더해져 총 16개이며, 구현 클래스명과 패키지는 이 문서에서 확정하지 않는다.

```
com.itda
├── global/                  # 공통
│   ├── config/, error/, response/
│   ├── security/            # [예정] Access Token 공통 인증·역할 인가
│   └── braille/             # BraillePattern — 6비트 셀 shared kernel
├── user/                    # users — 계정·인증. 아래 "계정·인증 목표 구조" 참고
│   ├── domain/              # [현재] User(애그리거트), Email(VO), UserRole, UserStatus, UserRepository(인터페이스)
│   ├── application/         # [일부 현재] 비밀번호 정책 유스케이스 + port/out (PasswordHasher·CompromisedPasswordChecker)
│                            # [예정] JWT·Redis·메일 outbound port와 나머지 유스케이스
│   ├── presentation/        # [예정] HTTP DTO·상태 코드·쿠키/body 변환
│   └── infrastructure/      # [현재] UserRepositoryImpl(JPA 어댑터), UserJpaRepository,
│                            #        Argon2idPasswordHasher, HibpRangePasswordChecker, 설정 properties·구성
│                            # [예정] JWT·Redis·메일 adapter
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
| `global/security` [예정] | Access Token 공통 인증, 역할 기반 인가 | 로그인·회원가입·Refresh 검증 |

**Port 배치.** `UserRepository`는 위 규칙 2번대로 `user/domain`에 둔다 — 도메인 애그리거트를 되살리는 수단이라
도메인 언어의 일부다. JWT·Redis·메일·비밀번호 해시처럼 바깥에 요청하는 기능의 outbound port는
`user/application/port/out`에 두고 기술 구현은 `user/infrastructure`에 둔다.
**`domain`은 이 port들을 직접 호출하지 않는다** — 호출 순서와 트랜잭션 경계를 정하는 것은 `application`이다.

**인증 경계.** `global/security`는 Access Token 기반 보호 API의 공통 인증과 역할 인가를 담당한다.
로그인·토큰 갱신·이메일 인증·비밀번호 복구의 자격 증명 검증은 각 `application` 유스케이스가 담당한다.

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
