# 코드 스타일 — backend (Spring Boot)

## 원칙

- 일관성을 개인 선호보다 우선한다. 포맷은 리뷰 대상이 아니라 자동화 대상이다.
- 이름은 구현 방식보다 의도를 드러낸다. 주석은 "무엇"이 아니라 "왜"를 설명한다.
- 도메인 용어는 프로젝트 전체에서 통일한다 (learner/user, lesson/class 혼용 금지).

## 자동화 도구

- 포맷: Spotless + google-java-format (Gradle 연결, CI에서 강제 — 도입 예정)
- 스타일 기준: Google Java Style

## 코드 작성

- 한 메서드는 한 가지 책임. 길어지면 검증·변환·저장 단위로 분리한다.
- 매직 넘버·문자열은 상수 또는 enum으로 분리한다.
- null 반환보다 Optional, 빈 컬렉션, 명시적 예외를 우선한다.
- `var`는 지양하고 명시적 타입을 쓴다.

## 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 변수·메서드 | camelCase | `quizResult`, `gradeAnswer` |
| boolean | 상태가 드러나게 | `isCorrect`, `hasBrailleDevice` |
| 상수·enum 값 | UPPER_SNAKE_CASE | `MAX_DOT_COUNT`, `LESSON_COMPLETED` |
| 클래스·파일 | PascalCase | `LessonService`, `QuizController` |
| 패키지 | lowercase, 점 계층 | `com.ieum.lesson` |
| 컬렉션 | 복수형 | `lessons`, `wrongAnswerIds` |
| 테스트 클래스 | 대상클래스명 + Test | `LessonServiceTest` |

- DTO: 요청은 `XxxRequest`, 응답은 `XxxResponse` (`QuizSubmitRequest`, `LessonSummaryResponse`).
- Service 메서드는 업무 용어로: `assignQuiz()`, `gradeAnswer()`, `buildReviewPlan()`.
- Entity 클래스는 단수 명사, DB 테이블·컬럼은 snake_case로 매핑하고 테이블명은 복수형으로 통일한다.

```java
@Entity
@Table(name = "lessons")
public class Lesson {
  @Column(name = "created_at")
  private LocalDateTime createdAt;
}
```

## 구조와 예외 처리

- 패키지는 도메인 중심: `lesson/`, `quiz/`, `review/`, `member/` + 공통은 `global/`(config, error, security).
- Controller에는 요청/응답 변환만, 비즈니스 로직은 Service에, 도메인 규칙은 Entity에 둘 수 있다.
- 예외는 `@RestControllerAdvice`에서 공통 처리. 비즈니스 예외는 커스텀 예외 + 에러 코드.
- 응답에 스택 트레이스를 노출하지 않는다. 에러 응답 포맷은 api.md를 따른다.
- URL 설계는 api.md, 테스트는 ../quality/testing.md를 따른다.

## user 평면 패키지

계정·인증 컨텍스트 `com.itda.user`는 아래 다섯 패키지만 쓴다. 이 절이 `user` 패키지 배치의 원본 규칙이고, 기능별로 코드를 찾는 표와 경계의 설계 근거는 [apps/backend/README.md 「user 평면 패키지 탐색」](../../../apps/backend/README.md#user-평면-패키지-탐색)에 둔다. 다른 컨텍스트(`lecture`·`curriculum`·`problem`·`learning`)는 현재 `domain`만 있으며 이 절을 적용하지 않는다.

| 패키지 | 책임 | 두지 않는 것 |
| --- | --- | --- |
| `com.itda.user.controller` | HTTP 진입점(`@RestController`)과 상태 코드·응답 헤더, `X-Client-Platform` 판정과 요청 filter·등록, WEB/MOBILE 응답 방식 선택, Refresh 쿠키 조립과 전달 위치 판정 | 업무 규칙·트랜잭션, JWT 발급·검증, MariaDB·Redis 접근, Request·Response DTO 선언 |
| `com.itda.user.dto` | HTTP Request·Response DTO, Jackson 직렬화·역직렬화 지원, 응답 시각 표기, DTO와 Command·Result 사이의 변환 | Command·Result 선언, Entity·저장 모델, 쿠키·플랫폼 판정 |
| `com.itda.user.service` | 유스케이스와 트랜잭션 경계, Command·Result, 사용자 인증 상태 판정, 저장소가 아닌 외부 연동 계약과 구현(Access 발급, Refresh·action 발급·검증과 key ring, Argon2id·유출 조회, 메일 제공자·발송 정책), 이들을 조립하는 Configuration | HTTP DTO·쿠키·플랫폼 분기, `SecurityContext` 구성, JPA·Redis 구현 세부 |
| `com.itda.user.repository` | 저장 계약(`UserRepository`·`MailOutboxRepository`·`RefreshTokenStore`·`ActionTokenStore`)과 그 입력·결과 타입, Spring Data JPA와 MariaDB 조건부 UPDATE 구현, Redis Lua 실행·키 생성·환경 prefix 설정, 이들을 조립하는 Configuration | 유스케이스 순서, JWT 발급·검증, 오류 code 결정, HTTP 응답 |
| `com.itda.user.domain` | Entity(`User`·`MailOutboxEntry`), 값 객체(`Email`·`Username`), enum, 상태 전이와 정규화·불변식 | Repository 계약·구현, Spring Security·JWT·Redis·HTTP, 트랜잭션 경계 |

### 배치 규칙

- 다섯 패키지 아래에 하위 패키지를 만들지 않는다(`controller.support`, `dto.request`, `repository.redis`, `service.token` 모두 금지). `com.itda.user` 루트에도 클래스를 두지 않는다. `global.security` 아래에도 하위 패키지를 두지 않는다.
- 한 파일에 목적지가 다른 책임이 섞이면 파일을 나눠 각 패키지에 둔다. Spring Configuration은 조립하는 bean이 속한 패키지에 둔다.
- Configuration을 옮기거나 나눌 때 `@Bean` 메서드 이름을 바꾸지 않는다. 파라미터 이름 주입(`userRegistrationTransactionOperations`, Lua `RedisScript` bean)과 테스트의 bean 조회가 이름에 기댄다.
- 테스트는 검증 대상 운영 클래스와 같은 패키지에 둔다. 여러 패키지의 테스트가 함께 쓰는 공통 test fixture(테스트 key·stub·Redis 테스트 기반 클래스)는 test source의 `com.itda.support.*`에 둘 수 있다. 다른 패키지에서 package-private 멤버가 필요해지면 접근 제한을 넓히기 전에 배치를 다시 본다 — 테스트를 컴파일하려고 운영 코드를 `public`으로 바꾸지 않는다.
- 패키지를 옮겨도 테스트 클래스 이름과 `@Tag("mariadb")`·`@RequiresRealRedis`는 유지한다. Dockerfile은 `TEST-*RedisTest.xml`로 실제 Redis 실행을 확인하고, Gradle은 태그로 `mariadbTest` 대상을 고른다.
- Lua 스크립트는 `src/main/resources/redis/`에 두며 Java 패키지와 함께 옮기지 않는다.

### 의존 방향

| 출발 | 참조할 수 있다 | 참조하지 않는다 |
| --- | --- | --- |
| `user.controller` | `user.dto`, `user.service`(유스케이스·Command·Result), `user.domain`의 값, `global.security`(`AuthenticatedUser`), `global.error` | `user.repository` |
| `user.dto` | `user.service`의 Command·Result(변환용), `user.domain`의 값 | `user.controller`, `user.repository` |
| `user.service` | `user.repository`, `user.domain`, `global.security`의 JWT 계약(`AccessTokenVerifier`·`AccessJwtContract`·`JwtKeyContract`)과 Access 설정(`AccessJwtProperties`·`AccessKeyRing` — issuer 공유와 kid 교차 중복 검사), `global.error` | `user.controller`, `user.dto` |
| `user.repository` | `user.domain`, `global.config.RuntimeEnvironmentProfile`(실행 환경 판정) | `user.controller`, `user.dto`, `user.service` |
| `user.domain` | JPA·Lombok 애너테이션 외 없음 | `user.controller`, `user.dto`, `user.service`, `user.repository`, `global.security` |
| `global.security` | `user.service.AccessAuthenticationService`와 그 결과 타입, `user.domain.UserRole`, `global.config.RuntimeEnvironmentProfile` | 로그인·갱신·로그아웃·action token·비밀번호·메일 유스케이스, `user.repository` |

Controller는 Service를 거치지 않고 JPA·Redis 구현을 호출하지 않는다. Service는 HTTP Request·Response DTO를 받거나 돌려주지 않는다. `global.security`와 `user.service`는 Access 인증에서만 서로를 참조하며, 그 범위는 아래 경계로 한정한다.

### Access 인증 경계

| 책임 | 패키지 |
| --- | --- |
| `SecurityFilterChain`·보호 경로 matcher, Bearer 추출, Access 인증 filter, 401 진입점, principal(`AuthenticatedUser`) | `global.security` |
| Access JWT 검증 계약(`AccessTokenVerifier`)과 구현, Access JWT 계약 값(`AccessJwtContract`), Access key ring·설정(issuer 포함) | `global.security` |
| 서명 key `kid` 형식 검증(`JwtKeyContract`) — Access·Refresh·action 설정과 key ring이 공통으로 쓰는 형식 규칙일 뿐, Refresh·action token의 발급·검증은 맡지 않는다 | `global.security` |
| 실행 환경 프로필(`local`·`test`·`prod`) 판정(`RuntimeEnvironmentProfile`) — Access issuer와 Redis key prefix 검사가 함께 쓰며, 두 설정은 서로를 참조하지 않는다 | `global.config` |
| 사용자 인증 상태 판정(존재·`status`·이메일 인증·`auth_version`) — `AccessAuthenticationService` | `user.service` |
| Access JWT 발급(`AccessTokenIssuer`)과 로그인·갱신 처리 순서 | `user.service` |
| Refresh·action token 발급·검증과 Redis 상태 전이, 비밀번호, 메일 outbox | `user.service`·`user.repository` — `global`로 옮기지 않는다 |

Access JWT 발급과 검증이 한 클래스에 있으면 발급은 `user.service`, 검증은 `global.security`로 나눈다. 서명 알고리즘·`typ`·`aud`·유효기간 같은 Access JWT 계약 값은 `global.security` 한 곳에 두고 발급기에 복제하지 않는다.
