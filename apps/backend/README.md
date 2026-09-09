# backend — Spring Boot (Java 25)

## 스택

- Java 25 (Gradle toolchain이 자동으로 JDK 25를 내려받음)
- Spring Boot 4.0.x (Boot 4부터 web 스타터 이름이 `spring-boot-starter-webmvc`)
- Spring Data JPA — local·prod: MariaDB 11.8, test: H2(MySQL 모드)
- Lombok, Bean Validation

## 실행

```bash
# 전체 스택(웹·백엔드·AI·MariaDB) — 권장
docker compose up --build --watch          # 루트에서 실행. 상세: docs/infra/docker.md

# 백엔드만, 호스트에서
docker compose up -d db                    # MariaDB 먼저 띄우고
./gradlew bootRun                          # 기본 local 프로필 (localhost:3306)
```

- spring-boot-devtools 적용: 코드 재컴파일 시 애플리케이션 자동 재시작 (IntelliJ에서는 Build Project(Ctrl+F9) 시 반영)
- Health check: `GET http://localhost:8080/api/v1/health`

> gradlew 래퍼가 없다면 최초 1회 `gradle wrapper --gradle-version 9.2` 실행 (IntelliJ로 열면 자동 처리).

## 프로필과 DB

| 프로필 | DB | `ddl-auto` | 용도 |
| --- | --- | --- | --- |
| `local` (기본) | MariaDB 11.8 (compose `db` 서비스) | `update` | 개발 |
| `test` | H2 인메모리 (MySQL 모드) | `create-drop` | `./gradlew test`, `docker build --target test` |
| `prod` | MariaDB (RDS) | `validate` | 배포 |

`test`가 인메모리인 이유: MR verify 파이프라인의 `docker build --target test`는 DB 컨테이너도 네트워크도 없이 실행된다.
테스트 프로필은 `src/test/resources/application.properties`가 고정한다.

**스키마 원본은 엔티티다.** Flyway는 아직 도입하지 않았으므로 ERD의 CHECK 제약·`ON DELETE RESTRICT`·`DEFAULT
CURRENT_TIMESTAMP(6)`는 DB에 반영되지 않는다. 그 규칙은 엔티티 생성자·도메인 메서드의 불변식이 대신 보장하며, 각 엔티티 주석에 해당 ERD 절을 적어 두었다.
설계 근거: [docs/superpowers/specs/2026-09-09-backend-ddd-entities-design.md](../../docs/superpowers/specs/2026-09-09-backend-ddd-entities-design.md)

환경변수 키는 루트 `.env.example`을 따른다. 새 키를 추가하면 그 파일도 함께 갱신한다.

**`DB_PASSWORD`는 필수다.** 저장소에 자격증명을 남기지 않기 위해 `docker-compose.yml`과 `application.yml`
어디에도 비밀번호 기본값을 두지 않았다. 루트에서 `cp .env.example .env` 후 값을 채운다 — 없으면 `db` 서비스가
기동 단계에서 바로 실패한다.

## DDD 패키지 구조

ERD 15개 테이블을 5개 바운디드 컨텍스트로 나눴다 ([docs/product/erd.md](../../docs/product/erd.md)).

```
com.itda
├── global/                  # 공통
│   ├── config/, error/, response/
│   └── braille/             # BraillePattern — 6비트 셀 shared kernel
├── user/                    # users
│   ├── domain/              # User(애그리거트), Email(VO), UserRole, UserStatus, UserRepository(인터페이스)
│   └── infrastructure/      # UserRepositoryImpl(JPA 어댑터), UserJpaRepository
├── lecture/domain/          # Lecture(애그리거트), LectureProblem, Participant, ParticipantId, LectureStatus
├── curriculum/domain/       # CurriculumStage, CurriculumProblem, CurriculumProblemId
├── problem/domain/          # Problem(애그리거트 + ProblemCell), LearningSkill, ProblemSkill, enum 3개
└── learning/domain/         # LearningSession(애그리거트), ProblemAttempt(애그리거트 + AttemptCell·AttemptSkillResult),
                             # UserSkillMemory, AssignedProblem, CellSubmission, enum 4개
```

`application/`·`presentation/` 계층은 아직 없다. 유스케이스가 생기는 기능 이슈에서 해당 컨텍스트에 추가한다.
`user/`가 4계층 중 `domain`↔`infrastructure` 참조 패턴의 예시다.

### 규칙

1. `domain`은 Spring/JPA 외 어떤 계층에도 의존하지 않는다 (JPA 애너테이션은 실용적 타협).
2. Repository **인터페이스는 domain에**, 구현체는 infrastructure에 둔다 (의존성 역전).
3. 엔티티에 setter를 만들지 않는다. 상태 변경은 의미 있는 도메인 메서드로.
4. presentation의 Request/Response DTO와 application의 Command/Info를 분리한다.
5. 시각은 엔티티가 스스로 읽지 않고 인자로 받는다. ERD가 서버 영속 시각을 UTC DB 시각으로 못박고 자동 `ON UPDATE`를 금지하므로,
   한 트랜잭션의 여러 시각이 어긋나면 안 되고 테스트도 결정적이어야 한다.

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
