# backend DDD 도메인 구조·엔티티 설계

> 작성일: 2026-09-09 · 대상: `apps/backend` · 근거: [docs/product/erd.md](../../product/erd.md), [docs/conventions/code/backend.md](../../conventions/code/backend.md)

## 1. 배경과 범위

`apps/backend`에는 이미 Spring Boot 4.0.8 / Java 25 골격(Gradle wrapper, Spotless, `global/{config,error,response}`, 4스테이지 Dockerfile)이 있고, DDD 4계층 구조를 보여 주는 예시 `user/` 컨텍스트가 하나 있다. 그러나 그 `user/`는 ERD를 반영하지 않은 데모이고, ERD의 15개 테이블 중 실제로 매핑된 것은 없다.

이 문서는 **ERD 15개 테이블을 5개 바운디드 컨텍스트의 JPA 엔티티로 매핑하고, MariaDB를 실제 개발 DB로 배선하는 것**까지를 다룬다.

### 범위에 포함

- 5개 바운디드 컨텍스트 패키지 생성과 ERD 15개 테이블 전체의 엔티티·VO·enum
- 6비트 점자 셀 공용 VO (`BraillePattern`)
- MariaDB 배선: 드라이버 교체, `local`/`test`/`prod` 프로필 분리, `docker-compose.yml`에 MariaDB 서비스 추가
- 예시 `user/` 컨텍스트를 ERD 기준으로 교체
- 엔티티 매핑 검증 테스트

### 범위 밖 (별도 이슈)

| 항목 | 이유 |
| --- | --- |
| Redis 인증 상태 저장 (ERD 3.1.1) | 관계형 스키마가 아니며 인증 기능 구현과 함께 설계해야 한다 |
| Flyway 마이그레이션 | 아래 3절에서 엔티티를 스키마 원본으로 채택했다. 도입 시 baseline 작업이 필요하다 |
| ERD의 CHECK 제약·`ON DELETE RESTRICT` | ddl-auto 채택의 트레이드오프. DB가 아니라 엔티티 불변식·서비스 검증으로 보장한다 |
| SYSTEM 기본 문제 Seed (ERD 7절) | 콘텐츠 작업이며 버전 관리 파일·일회성 마이그레이션이 필요하다 |
| 모든 서비스·컨트롤러 | 이번 범위에 유스케이스가 없다 |

## 2. 패키지 구조

```
apps/backend/src/main/java/com/itda/
├── global/
│   ├── config/, error/, response/        기존 유지
│   └── braille/BraillePattern.java       신규 shared kernel
├── user/
│   ├── domain/         User, UserRole, UserStatus, Email, UserRepository
│   └── infrastructure/ UserJpaRepository, UserRepositoryImpl
├── lecture/domain/     Lecture, LectureStatus, LectureProblem, Participant, ParticipantId
├── curriculum/domain/  CurriculumStage, CurriculumProblem, CurriculumProblemId
├── problem/domain/     Problem, ProblemSourceType, ProblemType, ProblemCell,
│                       LearningSkill, SkillType, ProblemSkill, ProblemSkillId
└── learning/domain/    LearningSession, AssignedProblem, SessionStatus,
                        ProblemAttempt, AttemptMode, AttemptCell, AttemptSkillResult,
                        UserSkillMemory, UserSkillMemoryId, MasteryBand
```

컨텍스트 경계는 ERD 3·4·5절의 영역 구분을 따르되 커리큘럼과 문제를 분리했다. 각 패키지가 애그리거트 1~2개를 담는다.

`application/`·`presentation/` 계층은 **만들지 않는다.** 이번 범위에 유스케이스가 없어 빈 계층은 YAGNI다. `user/`가 4계층 참조 패턴을 README로 남기고, 새 컨텍스트는 기능 이슈에서 필요한 계층을 추가한다. `infrastructure/`도 기존 리포지토리가 있는 `user/`에만 둔다.

`BraillePattern`을 `global/braille`에 두는 근거: AGENTS.md가 6비트 셀을 전 구간 공통 표현으로 규정하고, `problem/`(정답 패턴)과 `learning/`(입력 패턴·차이 마스크)이 함께 쓴다. 어느 한 컨텍스트에 넣으면 다른 쪽이 그 컨텍스트를 import하게 된다. 즉 이것은 컨텍스트가 아니라 shared kernel이다.

## 3. 결정 사항과 근거

### 3.1 연관관계 매핑 — 애그리거트 경계 기준 혼합

| 관계 | 매핑 | 해당 |
| --- | --- | --- |
| 같은 애그리거트 자식 | 자식에 `@ManyToOne(LAZY)`, 루트에 `@OneToMany(cascade = ALL, orphanRemoval = true)` | `Problem→ProblemCell`, `ProblemAttempt→AttemptCell`, `ProblemAttempt→AttemptSkillResult` |
| 다른 애그리거트·컨텍스트 | `Long` FK 필드 | `userId`, `problemId`, `skillId`, `learningSessionId`, `curriculumStageId`, `lectureId`, `instructorUserId`, `creatorUserId` |
| 복합 PK 연결 테이블 | `@EmbeddedId` + Long 2개, 연관 객체 없음 | `participants`, `curriculum_problems`, `problem_skills`, `user_skill_memory` |

cascade를 적용한 3곳은 ERD가 "전체 검증 후 한 트랜잭션으로 저장, 실패 시 일부도 저장하지 않음"을 명시한 위치(ERD 4.3·5.3절)와 정확히 일치한다. 그 밖의 참조는 Long으로 두어 컨텍스트 간 결합과 의도치 않은 지연 로딩을 구조적으로 막는다.

**의도적 예외**: `lectures.current_lecture_problem_id`는 같은 컨텍스트·같은 애그리거트지만 `Long`으로 둔다. `lectures ↔ lecture_problems`가 순환 FK(ERD 3.2·3.4절)이므로 양방향 객체 참조로는 insert 순서를 결정할 수 없다.

`Lecture`에 `@OneToMany List<LectureProblem>`도 두지 않는다. 수업 문제 수에 상한이 없고 ERD가 CLOSED 상태에서 연결 삭제를 금지하므로 cascade·orphanRemoval의 이득이 없다.

### 3.2 스키마 원본 — 엔티티 (Hibernate ddl-auto)

엔티티를 스키마 원본으로 삼고 Flyway는 후속 이슈로 분리한다.

**트레이드오프**: ERD의 CHECK 제약(`score` 0~1, `expected_pattern` 0~63, `total_seen = total_correct + total_incorrect`), `ON DELETE/UPDATE RESTRICT`, `DEFAULT CURRENT_TIMESTAMP(6)`는 DB에 반영되지 않는다. 대신 **엔티티 생성자·도메인 메서드의 불변식으로 보장**하고, 각 엔티티에 해당 ERD 절을 주석으로 남겨 Flyway 도입 시 옮길 대상을 명시한다.

### 3.3 파생 상태에 컬럼을 만들지 않는다

ERD가 명시적으로 금지한 항목이다.

- `LearningSession.status()` → `IN_PROGRESS | ANALYSIS_PENDING | ANALYZED`. `completed_at`·`analysis_applied_at` 두 시각으로 판정한다 (ERD 5.5절: "status·current_item_no·paused_at·updated_at 컬럼은 추가하지 않는다")
- `UserSkillMemory.masteryBand()` → `WEAK | LEARNING | MASTERED`. `mastery_probability`의 0.70·0.95 경계로 판정한다 (ERD 5.4절: "상태는 컬럼 없이 파생한다"). 행 없음 = 미관측은 `Optional`로 표현하며 enum 값으로 만들지 않는다

### 3.4 엔티티 행위의 범위

setter 금지 원칙(backend README 규칙 3)을 유지하되, **ERD가 명시한 규칙만** 도메인 메서드로 옮긴다.

| 엔티티 | 도메인 메서드 | ERD 근거 |
| --- | --- | --- |
| `User` | `verifyEmail`, `recordLoginFailure`(5회→10분 잠금), `resetLoginFailures`, `withdraw` | 3.1절 |
| `LearningSession` | `status()`, `markCompleted`, `markAnalysisApplied` (모두 최초 한 번만) | 5.5절 |
| `UserSkillMemory` | `masteryBand()`, `applyObservation` (`total_seen = total_correct + total_incorrect` 유지) | 5.4절 |
| `AttemptSkillResult` | 생성 시 `isCorrect=true ⇒ errorCount=0`, `false ⇒ errorCount ≥ 1` 검증 | 5.3절 |
| `BraillePattern` | `of`(0~63 검증), `diff`(XOR), `dotCount` | 4.4절 |

추론이 필요한 규칙(탈퇴 시 가명값 생성, 채점 알고리즘, BKT 계산)은 넣지 않고 서비스 계층 이슈로 넘긴다. `withdraw`는 가명 username·email·폐기 비밀번호 해시를 인자로 받아, 엔티티는 `status`/`withdrawn_at`/`email_verified_at` 동시 전이라는 불변식만 강제한다.

## 4. ERD → Java 타입 매핑

| ERD | Java | 비고 |
| --- | --- | --- |
| `BIGINT` PK AUTO_INCREMENT | `Long` + `@GeneratedValue(IDENTITY)` | ERD 7절: 독립 id 11개 테이블 |
| `DATETIME(6)` | `LocalDateTime` | DB 세션 TZ = UTC, `hibernate.jdbc.time_zone: UTC`. ERD 7절이 자동 `ON UPDATE`를 금지하므로 `@UpdateTimestamp`를 쓰지 않는다 |
| `ENUM` | `@Enumerated(EnumType.STRING)` + enum | `UserRole`의 `TEACHER`를 ERD대로 `INSTRUCTOR`로 수정 |
| `TINYINT` 0~63 | `@Embeddable BraillePattern { int value }` | 컬럼명이 다르거나 한 엔티티에 여러 번 쓰이므로 `@AttributeOverride`로 재정의한다. 이때 `nullable`·`columnDefinition`을 **매번 다시 명시**해야 한다 (7.3절) |
| `DECIMAL(5,4)` / `DECIMAL(7,6)` / `DECIMAL(10,4)` | `BigDecimal` + `@Column(precision, scale)` | |
| `CHAR(36)` | `String` + `columnDefinition = "CHAR(36)"` | `idempotency_key`, `creation_idempotency_key` |
| `CHAR(6)` | `String` + `columnDefinition = "CHAR(6)"` | `participation_code` — 앞자리 0 보존 |
| `JSON` (`assigned_problems`) | `@JdbcTypeCode(SqlTypes.JSON) List<AssignedProblem>` | `AssignedProblem`은 `record(int itemNo, String problemId)`. ERD 5.5절대로 **problemId는 10진수 문자열** |
| `BOOLEAN` | `boolean` | |
| `TEXT` | `String` + `columnDefinition = "TEXT"` | |

## 5. 설정 변경

- **build.gradle**: `com.mysql:mysql-connector-j` → `org.mariadb.jdbc:mariadb-java-client`. `h2`는 테스트용으로 유지
- **application.yml** 프로필 3개
  - `local` → `jdbc:mariadb://${DB_HOST:localhost}:3306/itda`, `ddl-auto: update`
  - `test` → H2 MySQL 모드, `create-drop`. **필수**: `docker build --target test`가 DB·네트워크 없이 실행되어야 한다
  - `prod` → `${DB_URL}`, `ddl-auto: validate`
  - H2 콘솔 설정 제거 (`local`이 더 이상 H2가 아니다)
- **src/test/resources**: 테스트가 `test` 프로필로 뜨게 한다 (`ItdaApplicationTests`의 `@ActiveProfiles("local")` 교체)
- **docker-compose.yml**: `db` 서비스(`mariadb:11.8`, utf8mb4, TZ=UTC, healthcheck, `mariadb-data` named volume) 추가, `backend.depends_on: db(service_healthy)`. 루트 비밀번호는 `MARIADB_RANDOM_ROOT_PASSWORD`로 매번 새로 만들고, 앱 계정 비밀번호는 **인라인 기본값 없이** `.env`에서만 주입한다 (7.5절)
- **.env.example**: MariaDB 변수로 갱신 (AGENTS.md: 환경변수 키 추가 시 함께 갱신)
- **apps/backend/README.md**: 스택·실행·패키지 구조 갱신
- **삭제**: `user/application/*`, `user/presentation/*` (ERD·api-spec과 무관한 데모, 외부 참조 없음을 확인함), `user/repository/UserRepository.java` → `user/domain/`으로 이동 (현재 디렉토리와 패키지 선언이 불일치)

## 6. 완료 기준

"했다"가 아니라 아래가 **참임을 확인**해서 보고한다.

1. `cd apps/backend && ./gradlew spotlessApply test` 통과
2. `docker build --target test apps/backend` 통과 (MR verify 파이프라인과 동일)
3. `docker compose up -d db backend` 후 MariaDB에 15개 테이블 생성 확인 — `SHOW TABLES`가 15행

테스트 구성 (docs/conventions/quality/testing.md)

- `BraillePatternTest` — 0·63 경계, 64·-1 거부, XOR 차이 계산. 6비트 셀 로직의 경계값 테스트를 컨벤션이 명시한다
- 컨텍스트별 `@DataJpaTest` — persist/find 라운드트립, `@EmbeddedId` 복합 PK, `assigned_problems` JSON 라운드트립, cascade 저장

## 7. 구현 결과와 설계 시점과의 차이

구현·검증을 마친 뒤 실제로 확인된 사항이다. 설계 시점의 가정과 다른 부분을 남긴다.

### 7.1 추가된 것

| 항목 | 이유 |
| --- | --- |
| `global/config/Jackson3JsonFormatMapper` | Boot 4의 Jackson 3과 Hibernate 7.2의 Jackson 2 기대가 어긋나 JSON 매핑이 저장 시점에 실패했다. 기록: [docs/solutions/2026-09-09-boot4-jackson3-hibernate-json.md](../../solutions/2026-09-09-boot4-jackson3-hibernate-json.md) |
| `testImplementation 'org.springframework.boot:spring-boot-data-jpa-test'` | Boot 4가 `@DataJpaTest`·`TestEntityManager`를 모듈별 아티팩트로 분리했다. 같은 solutions 문서의 곁가지 절 참고 |
| `apps/backend/gradlew` 실행 권한 (100644 → 100755) | AGENTS.md의 검증 명령 `cd apps/backend && ./gradlew test`가 로컬에서 실행되지 않았다. Dockerfile은 `chmod +x`로 우회하고 있었다 |

### 7.2 ERD와 실제 생성 스키마의 차이

`ddl-auto`를 스키마 원본으로 택한 결과다. 실제 MariaDB 11.8에서 확인했다.

| 컬럼 | ERD | 생성 결과 | 판단 |
| --- | --- | --- | --- |
| `assigned_problems` | `JSON` | `longtext` | **허용.** ERD 7절 스스로 "MariaDB의 JSON은 LONGTEXT COLLATE utf8mb4_bin 기반 별칭"이라 적었다. 차이는 `JSON_VALID` CHECK뿐이고 ERD도 그 검증을 백엔드 책임으로 규정한다 |
| `role`·`status`·`source_type`·`problem_type`·`skill_type`·`attempt_mode` | `ENUM` | 네이티브 `enum(...)` | **설계보다 좋음.** `@Enumerated(STRING)`이 VARCHAR가 될 것으로 예상했으나 Hibernate 7이 MariaDB 네이티브 ENUM을 생성해 ERD와 정확히 일치한다 |
| 모든 CHECK 제약, `ON DELETE/UPDATE RESTRICT`, `DEFAULT CURRENT_TIMESTAMP(6)` | 존재 | 없음 | **알려진 트레이드오프** (3.2절). 엔티티 불변식이 대신 보장하며 Flyway 이슈에서 DB로 옮긴다 |

### 7.3 구현 중 발견해 고친 결함

`@AttributeOverride`가 Embeddable의 `@Column`을 병합하지 않고 통째로 교체하는 탓에, `BraillePattern`에 선언한 `TINYINT NOT NULL`이 버려지고 `int(11) NULL`로 생성됐다. 컴파일·테스트는 전부 통과했다 — 타입이 넓어지고 NULL이 허용된 것만으로는 어떤 검사도 실패하지 않는다.

재정의 시 컬럼 속성 전체를 다시 명시해 고쳤다. 기록: [docs/solutions/2026-09-09-attributeoverride-column-replace.md](../../solutions/2026-09-09-attributeoverride-column-replace.md)

**교훈**: `ddl-auto`를 쓰는 동안에는 엔티티를 추가·수정한 뒤 `information_schema.columns`로 실제 물리 타입을 ERD와 대조해야 한다. 매핑 테스트는 값 왕복만 보므로 이 계열을 잡지 못한다.

### 7.4 검증 결과

| 완료 기준 | 결과 |
| --- | --- |
| `./gradlew spotlessCheck test` | 통과 — 132개 테스트, 실패 0 |
| `docker build --target test apps/backend` | 통과 |
| MariaDB 15개 테이블 생성 | 통과 — `SHOW TABLES` 15행, ERD 테이블 목록과 정확히 일치 |
| MariaDB JSON 왕복 (추가 확인) | 통과 — 저장 원문이 `[{"itemNo": 1, "problemId": "101"}, …]`로 problemId가 문자열 보존 |

### 7.5 DB_PASSWORD에 기본값을 두지 않는 이유

애초 설계는 `${DB_PASSWORD:-itda}`처럼 로컬 전용 기본값을 둬서 `.env` 없이도 기동되게 하려 했다. 그런데 gitleaks의 `itda-db-password` 규칙이 이 형태를 하드코딩으로 판정해 커밋이 차단됐다.

규칙의 정규식이 `${...}` **안쪽**을 본다. `${DB_PASSWORD:-itda}`에서 `DB` + `_` + `PASSWORD` + `:` + `-itda}`가 "DB 비밀번호 대입"으로 매칭된다. 전역 allowlist의 `\$\{[A-Z_]+\}`는 기본값 없는 형태만 커버하고, `changeme`·`example` allowlist도 추출되는 secret이 캡처 그룹(`DB`)이라 걸리지 않는다. 실측으로 확인했다 — 기본값이 없는 `${DB_PASSWORD}`만 통과한다.

규칙을 완화하는 대신 **기본값을 없애고 `.env`를 필수로** 했다. 저장소에 자격증명 문자열이 아예 남지 않는다.

미설정 시 실패 지점을 앞으로 당겼다. MariaDB 공식 이미지는 빈 `MARIADB_PASSWORD`를 거부하지 않고 그대로 기동하므로(실측), 그냥 두면 db는 뜨고 backend만 placeholder 오류로 죽는 혼란한 조합이 된다. `db` 서비스의 `env_file`을 `required: true`로 두어 `.env`가 없으면 compose 단계에서 파일명을 알려 주며 즉시 실패하게 했다.

**남은 불일치**: 기존 `deploy/docker-compose.prod.yml`은 `${DB_PASSWORD:?DB_PASSWORD is required}`를 쓰고 있고 같은 규칙에 걸린다(워킹트리 스캔으로 확인). 이미 병합된 파일이라 이번 작업에서 고치지 않았다 — 그 파일을 다시 수정할 때 드러날 문제다.
