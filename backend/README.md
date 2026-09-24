# backend — Spring Boot (Java 25)

## 스택

- Java 25 (Gradle toolchain이 자동으로 JDK 25를 내려받음)
- Spring Boot 4.0.x (Boot 4부터 web 스타터 이름이 `spring-boot-starter-webmvc`)
- Spring Data JPA — local: H2(MySQL 모드), prod: MySQL
- Lombok, Bean Validation

## 실행

```bash
./gradlew bootRun                       # 기본 local 프로필 (H2)
./gradlew bootRun --args='--spring.profiles.active=prod'
```

- Health check: `GET http://localhost:8080/api/v1/health`
- 회원가입 예시: `POST http://localhost:8080/api/v1/auth/signup`
- H2 콘솔(local): `http://localhost:8080/api/v1/h2-console`

> gradlew 래퍼가 없다면 최초 1회 `gradle wrapper --gradle-version 9.2` 실행 (IntelliJ로 열면 자동 처리).

## DDD 패키지 구조

```
com.itda
├── global/                  # 공통 (config, error, response)
└── user/                    # Bounded Context 예시 — 새 도메인은 이 구조를 복제
    ├── domain/              # User(애그리거트), Email(VO), UserRole, UserRepository(인터페이스)
    ├── application/         # UserService(유스케이스), SignupCommand, UserInfo
    ├── infrastructure/      # UserRepositoryImpl(JPA 어댑터), UserJpaRepository
    └── presentation/        # UserController, dto/
```

### 규칙

1. `domain`은 Spring/JPA 외 어떤 계층에도 의존하지 않는다 (JPA 애너테이션은 실용적 타협).
2. Repository **인터페이스는 domain에**, 구현체는 infrastructure에 둔다 (의존성 역전).
3. 엔티티에 setter를 만들지 않는다. 상태 변경은 의미 있는 도메인 메서드로.
4. presentation의 Request/Response DTO와 application의 Command/Info를 분리한다.
5. 새 도메인(classroom, problem, grading, learning, review, report, device, admin)은 `user/` 패키지 구조를 그대로 복제해서 시작한다.
