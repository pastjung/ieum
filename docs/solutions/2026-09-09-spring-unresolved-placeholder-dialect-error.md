# bootRun이 "Unable to determine Dialect"로 죽는 문제 — 원인은 dialect가 아니다

## 증상

`./gradlew bootRun`이 기동 실패한다.

```
Failed to initialize JPA EntityManagerFactory: Unable to create requested service
[org.hibernate.engine.jdbc.env.spi.JdbcEnvironment] due to: Unable to determine Dialect
without JDBC metadata (please set 'jakarta.persistence.jdbc.url' for common cases or
'hibernate.dialect' when a custom Dialect implementation must be provided)
```

메시지는 URL이나 dialect 설정이 없다고 말하지만 둘 다 정상이다. **이 메시지를 그대로 믿고 `hibernate.dialect`를 지정하면 안 된다** — 실제 원인을 덮을 뿐이다.

## 원인

로그를 위로 더 읽으면 진짜 원인이 나온다.

```
o.m.jdbc.message.server.ErrorPacket : Error: 1045-28000: Access denied for user 'itda'@'...' (using password: YES)
org.hibernate.orm.jdbc : HHH100046: Could not obtain connection to query JDBC database metadata
```

DB 접속이 인증 단계에서 거부되면 Hibernate가 메타데이터를 못 읽고, 그 결과 dialect를 결정할 수 없다고 보고한다. **dialect 메시지는 2차 증상이다.**

그리고 왜 인증이 틀렸나 — **`./gradlew bootRun`은 저장소 루트의 환경 파일을 읽지 않는다.** 그 파일은 docker compose가 `env_file`로 읽어 컨테이너에 주입하는 것이고, Gradle 실행에는 그런 경로가 없다.

여기에 Spring의 동작이 겹쳐 진단을 어렵게 만든다.

> **Spring은 미해결 플레이스홀더에 예외를 던지지 않는다.** `${DB_PASSWORD}`를 해결하지 못하면 리터럴 문자열 `"${DB_PASSWORD}"`를 그대로 비밀번호로 넘긴다.

그래서 "설정이 없다"가 아니라 "비밀번호가 틀렸다"로 나타난다. 로그의 `using password: YES`가 그 증거다 — 값이 비어 있지 않고, 무의미한 값이 실제로 전송됐다는 뜻이다.

## 해결

앱이 환경 파일을 직접 읽게 한다. `apps/ai`가 pydantic `SettingsConfigDict(env_file=...)`로 하는 것과 같은 규약이다.

`apps/backend/src/main/resources/application.yml`의 `local` 프로필:

```yaml
spring:
  config:
    activate:
      on-profile: local
    import:
      - optional:file:../../.env[.properties]
      - optional:file:./.env[.properties]
```

- `[.properties]`가 확장자 없는 파일을 properties 형식으로 파싱하게 한다 (`KEY=value`, `#` 주석 호환).
- `optional:`이라 파일이 없거나 컨테이너 안이면 조용히 무시된다 — `docker build --target test`가 영향받지 않는다.
- 경로가 `../../`인 이유: `bootRun`의 작업 디렉토리가 `apps/backend`이고 환경 파일은 저장소 루트에 있다.
- **OS 환경변수가 이 파일보다 우선한다.** compose가 주입한 값이 항상 이기므로 컨테이너 실행 동작은 바뀌지 않는다.

검증 방법 — 셸에 값이 없는 상태와 일부러 틀린 값을 준 상태를 대조한다.

```bash
# 파일에서 읽어 기동 성공해야 한다
(cd apps/backend && ./gradlew bootRun)

# 환경변수 우선순위 확인: 틀린 값을 주면 Access denied로 실패해야 한다
(cd apps/backend && DB_PASSWORD=deliberately-wrong-value ./gradlew bootRun)
```

## 재발 방지

- **"Unable to determine Dialect"를 보면 그 줄만 읽지 말고 위로 올라가 접속 오류를 찾는다.** 원인은 URL 누락, 인증 실패, DB 미기동 중 하나이며 메시지는 셋을 구분해 주지 않는다.
- 비밀번호에 기본값을 두지 않는 설정은 "값이 없으면 명확히 실패한다"를 보장하지 않는다. Spring이 리터럴을 넘기기 때문이다. 명확한 실패가 필요하면 파일·환경변수 로드 경로 자체를 보장해야 한다.
- 새 환경변수를 추가할 때 **compose 경로와 호스트 실행 경로 양쪽**에서 값이 도달하는지 확인한다. 한쪽만 검증하면 이 문제가 다시 난다.
