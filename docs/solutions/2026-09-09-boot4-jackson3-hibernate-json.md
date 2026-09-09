# Spring Boot 4에서 JSON 컬럼 매핑이 FormatMapper 없음으로 죽는 문제

## 증상

`@JdbcTypeCode(SqlTypes.JSON)`을 붙인 필드를 persist하는 순간 예외가 난다. 스키마 생성은 정상이고 저장 시점에만 터진다.

```
org.hibernate.HibernateException: Could not find a FormatMapper for the JSON format,
which is required for mapping JSON types. JSON FormatMapper configuration is automatic,
but requires that you have either Jackson or a JSONB implementation like Yasson on the class path.
```

Jackson은 분명히 클래스패스에 있다 — `spring-boot-starter-webmvc`가 끌어온다. 그래서 메시지가 사실과 어긋나 보인다.

## 원인

**Jackson 2와 Jackson 3의 패키지가 다르다.**

- Spring Boot 4는 Jackson 3을 쓴다 → `tools.jackson.databind.ObjectMapper`
- Hibernate 7.2가 번들한 `org.hibernate.type.format.jackson.JacksonJsonFormatMapper`는 Jackson 2를 찾는다 → `com.fasterxml.jackson.databind.ObjectMapper`

Hibernate의 자동 탐지는 `com.fasterxml` 클래스를 리플렉션으로 로드해 보고 실패하면 "Jackson 없음"으로 판단한다. Jackson 3만 있는 환경에서는 항상 실패한다.

확인 방법:

```bash
# 클래스패스의 Jackson 좌표를 본다. tools.jackson 계열이면 Jackson 3이다
./gradlew dependencies --configuration testRuntimeClasspath | grep -i jackson
```

## 해결

Jackson 2를 추가로 끌어오면 한 프로젝트에 두 Jackson이 공존한다. 대신 이미 있는 Jackson 3에 어댑터를 붙인다. `AbstractJsonFormatMapper`를 상속하면 메서드 두 개로 끝난다.

`apps/backend/src/main/java/com/itda/global/config/Jackson3JsonFormatMapper.java`:

```java
public class Jackson3JsonFormatMapper extends AbstractJsonFormatMapper {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Override
  protected <T> T fromString(CharSequence charSequence, Type type) {
    return objectMapper.readValue(charSequence.toString(), objectMapper.constructType(type));
  }

  @Override
  protected <T> String toString(T value, Type type) {
    return objectMapper.writeValueAsString(value);
  }
}
```

Hibernate가 설정값으로 리플렉션 생성하므로 **기본 생성자가 필요하다.** `application.yml`에 등록한다:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        type.json_format_mapper: com.itda.global.config.Jackson3JsonFormatMapper
```

프로필별이 아니라 공통 블록에 둬야 local·test·prod 전부 적용된다.

## 재발 방지

- 이 설정이 지워지면 JSON 컬럼을 쓰는 엔티티가 **저장 시점에** 죽는다. 컨텍스트 로드나 스키마 생성은 통과하므로 `@SpringBootTest` 하나로는 안 잡힌다. `learning_sessions.assigned_problems` 같은 JSON 컬럼에는 **실제 persist/find 왕복 테스트**를 둔다 (`LearningJpaMappingTest`).
- Hibernate가 Jackson 3을 정식 지원하면 이 어댑터와 설정을 함께 지운다.

## 곁가지: Boot 4에서 @DataJpaTest를 못 찾는 문제

같은 작업에서 함께 걸렸던 문제다. Boot 4는 슬라이스 테스트 애너테이션을 `spring-boot-starter-test`에서 **모듈별 아티팩트로 분리했고 패키지도 옮겼다.**

| 대상 | Boot 3 패키지 | Boot 4 패키지 | 필요한 의존성 |
| --- | --- | --- | --- |
| `@DataJpaTest` | `org.springframework.boot.test.autoconfigure.orm.jpa` | `org.springframework.boot.data.jpa.test.autoconfigure` | `spring-boot-data-jpa-test` |
| `TestEntityManager` | `org.springframework.boot.test.autoconfigure.orm.jpa` | `org.springframework.boot.jpa.test.autoconfigure` | (위 의존성이 전이로 가져옴) |
| `@AutoConfigureTestDatabase` | `org.springframework.boot.test.autoconfigure.jdbc` | `org.springframework.boot.jdbc.test.autoconfigure` | (같음) |

```gradle
testImplementation 'org.springframework.boot:spring-boot-data-jpa-test'
```

버전은 적지 않는다 — `io.spring.dependency-management`가 Boot 버전에 맞춰 관리한다. 버전을 직접 쓰면 코어와 스큐가 난다.

옮겨진 패키지명은 추측하지 말고 클래스패스에서 확인하는 게 빠르다:

```bash
# 클래스가 실제로 어느 jar의 어느 패키지에 있는지 찾는다
unzip -Z1 <jar> | grep -E 'DataJpaTest\.class$' | sed 's|/|.|g; s|\.class$||'
```
