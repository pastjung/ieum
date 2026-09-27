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

### 로그 민감정보 마스킹

콘솔 로그는 `src/main/resources/logback-spring.xml`이 Boot 기본 콘솔 설정을 그대로 쓰되 encoder만 `SensitiveDataMaskingEncoder`로
바꿔 내보낸다. pattern이 조합한 최종 문자열(message·예외 message·stack trace·원인 체인)을 `SensitiveLogMasker`가 훑어 다음 값을
`[REDACTED]`로 바꾼다. Hibernate 기동 연결 정보 로그에는 driver가 자격 증명을 붙인 JDBC URL이, MariaDB UNIQUE 위반 로그에는 가입 요청의
username·email이 원문으로 실리기 때문이다.

- Hibernate 연결 정보의 `Database JDBC URL [...]` 줄 — 레이블 뒤를 통째로 바꿔 `Database JDBC URL [REDACTED]`로 남긴다 — 와 `Default catalog/schema` 값
- 그 밖의 JDBC URL — `jdbc:<driver>:`부터 같은 줄 끝까지
- URL 밖에 찍힌 `user`·`username`·`password`·`passwd`·`pwd` 파라미터 — 이름 뒤부터 같은 줄 끝까지
- `Duplicate entry '<값>'`의 값 — 오류 번호와 key 이름은 남긴다

JDBC URL과 접속 계정 파라미터는 값의 끝을 믿지 않는다. driver는 사용자·비밀번호를 인코딩하지 않고 URL에 붙이고, 비밀번호에는 공백·괄호·따옴표·`&`·`;`·`,`가
들어갈 수 있다. 경계를 추측해 값 일부를 남기느니 같은 줄에 뒤따르는 비민감 진단 문구까지 지우는 쪽을 택한다. 다음 줄은 그대로 남는다.

logger를 끄거나 level을 올리지 않는다 — 저장소 오류가 났다는 사실과 logger·level·requestId까지 사라진다. 가리는 대상은 위에 적은 형식뿐이며,
등록되지 않은 새 민감정보 형식을 자동으로 찾아내지는 않는다. 마스킹 처리 중 예외가 발생하거나 결과가 null이면 원문 대신 level·logger·requestId와
고정 문구만 남긴다. 파일 appender와 구조화 로그(`logging.structured.*`)는 지금 쓰지 않으며, 추가할 때는 같은 encoder를 거치게 한다.

### 계정 메일 발송 (SMTP outbox)

이메일 인증·비밀번호 재설정 메일은 `mail_outbox`에 접수된 뒤(ERD 3.5절) worker가 SMTP로 보낸다. 접수(요청 API의 `202`)는 항상 되지만
**발송 실행은 기본값이 꺼져 있다.** 꺼진 환경에서는 어떤 메일도 나가지 않으며 `PENDING` 행만 쌓인다 — "꺼짐"은 "준비됨"이 아니다.
운영 readiness(SMTP 연결 확인·헬스 체크 반영)는 #19 범위다.

| 설정 (`application.yml` 환경변수) | 필수 | 기본값 | 의미 |
| --- | --- | --- | --- |
| `MAIL_DISPATCH_ENABLED` | | `false` | `true`면 provider·worker·스케줄러를 함께 조립한다. 하나만 생기는 조합은 없다 |
| `MAIL_SMTP_HOST` / `MAIL_SMTP_PORT` | 켜면 필수 | 없음 | 제공자 host·포트. 포트는 관례값(465·587)에 고정하지 않는다 |
| `MAIL_SMTP_SECURITY` | 켜면 필수 | 없음 | `SSL`(연결부터 TLS) 또는 `STARTTLS`(승격 필수, 실패 시 평문으로 계속하지 않음). `PLAINTEXT`는 `test` 프로필 + 루프백 host에서만 허용 |
| `MAIL_SMTP_USERNAME` / `MAIL_SMTP_PASSWORD` | `SSL`·`STARTTLS`면 필수 | 없음 | SMTP AUTH(계정·비밀번호)만 지원한다. OAuth·HTTP API는 다루지 않는다 |
| `MAIL_FROM_ADDRESS` | 켜면 필수 | 없음 | 발신 주소. 표시 이름 없는 단일 주소 |
| `MAIL_FROM_NAME` | | `이음(I:EUM)` | 환경변수를 생략하면 서비스 표시 이름을 쓴다. 이름을 교체할 수 있으며, 명시적 빈 값은 기존 정책대로 표시 이름을 생략한다. SMTP 인증 계정·발신 주소는 바꾸지 않는다 |
| `MAIL_REPLY_TO` | | 없음 | 선택. 비우면 `Reply-To` 헤더를 만들지 않는다 |
| `MAIL_LINK_EMAIL_VERIFICATION_URL` / `MAIL_LINK_PASSWORD_RESET_URL` | 켜면 필수 | 없음 | 메일 링크의 프론트 주소. `local`·`test`에서만 루프백 http 허용, `prod`는 루프백도 https 필수. 외부 주소는 모든 환경에서 https만 허용. 사용자 정보·질의·fragment 금지 |

켜졌는데 값이 빠지거나 형식이 틀리면(미해결 `${...}` 플레이스홀더 포함) 기동 단계에서 실패한다. `MAIL_DISPATCH_ENABLED`에 `true`·`false` 외의 값(`yes`, `on`, `1`, 오타)이
오면 조용히 꺼진 채 기동하지 않고 실패한다. 실패 원인 예외와 Boot의 기동 실패 출력 어디에도 자격증명 값은 실리지 않는다(테스트가 실제 로그 출력을 검사한다).

선택값인 계정·비밀번호·표시 이름·회신 주소는 null·빈 문자열·공백만 있는 문자열을 "없음"으로 정규화한다. 다만 `SSL`·`STARTTLS`는 그 뒤 계정·비밀번호가
모두 있는지 검증하므로 빈 자격증명으로 인증을 생략하지 않는다. 비어 있지 않은 값은 앞뒤 공백까지 그대로 보존하며 trim하지 않는다. 회신 주소의 앞뒤 공백 등
주소 형식 오류도 자동 보정하지 않고 거부한다. `MAIL_FROM_NAME`을 생략했을 때의 YAML 기본값과 명시적으로 빈 값으로 표시 이름을 생략하는 경우는 다르다.
발송 활성화 시 공통 프로필 판정으로 환경을 확정한 뒤 링크 정책을 검증한다. `prod`와 `local`·`test`를 동시에 켜서 HTTP 제약을 우회할 수 없다.

기동 검증용 SMTP 연결 점검이나 시험 메일 발송은 없다. 다만 **첫 poll은 기동 직후 즉시 돈다** — outbox에 대기 작업이 있으면 기동 직후 실제 발송이 시작된다.
연결·인증 문제는 그 첫 시도의 결과(`mail_outbox.last_error_kind`, 로그)로 드러난다.

환경변수 키를 두지 않은 값은 코드 기본값을 쓰며, 바꿔야 하면 Spring relaxed binding 이름(예: `ITDA_MAIL_DISPATCH_SCHEDULE_POLL_INTERVAL`)으로 넘긴다.

| 설정 키 | 기본값 | 허용 범위 | 의미 |
| --- | --- | --- | --- |
| `itda.mail.dispatch.schedule.poll-interval` | `10s` | 1s ~ 10m | outbox 선점·발송 주기. 첫 poll은 기동 직후 |
| `itda.mail.dispatch.schedule.cleanup-interval` | `5m` | 1m ~ 24h | 기한·시도 초과 종결과 보존 기간 지난 행 삭제 주기 |
| `itda.mail.dispatch.schedule.batch-size` | `20` | 1 ~ 500 | 한 poll이 선점하는 최대 건수 |
| `itda.mail.dispatch.schedule.shutdown-wait` | `30s` | 1s ~ 5m | 종료 시 진행 중 작업을 기다리는 상한. 넘기면 진행 중 SMTP 연결을 끊고 interrupt한 뒤 같은 시간만큼 실제 종료를 다시 기다린다(아래 「종료」). 컨테이너 정지 유예가 더 짧으면 그쪽이 먼저 끝낸다 |
| `itda.mail.provider.connect-timeout` / `read-timeout` / `write-timeout` | `10s` / `30s` / `30s` | 1ms ~ 2147483647ms | 소켓 단계별 제한(Angus는 밀리초 int로 받는다 — 0으로 잘리거나 int를 넘는 값은 무제한이 되므로 거부). 호출 전체의 상한은 `itda.mail.dispatch.provider-timeout`(계약 값 60s) 하나다 |

**링크와 토큰.** 메일 링크는 `<용도별 프론트 URL>#token=<action token>` 형식이다. 토큰은 fragment로만 실리므로 프론트 서버·프록시의 접근 로그에 남지 않는다.
클라이언트는 fragment의 토큰을 읽어 기존 완료 API(`POST /api/v1/email-verifications`, `POST /api/v1/password-resets`)의 body에 실어 호출한다.
백엔드는 GET·query parameter로 토큰을 받는 완료 endpoint를 두지 않는다. 본문은 UTF-8 text/plain이며 HTML·첨부·템플릿 파일이 없다.

**제공자 호출 결과의 분류(API 명세 3.8절)는 SMTP 단계로 정한다.** 연결·EHLO·STARTTLS·AUTH 단계에서 인증 거부, TLS 협상 실패, 서버는 정상 응답했지만
필수 요건인 STARTTLS를 광고하지 않은 경우(응답 코드와 원인 타입으로 판정)는 `AUTH_CONFIG_ERROR`, 그 밖의 연결 실패는 `RETRYABLE`. 봉투(MAIL·RCPT·DATA 명령)의
4xx는 `RETRYABLE`, 5xx는 인증 코드(530·534·535·538)와 발신 주소 거부면 `AUTH_CONFIG_ERROR`, 아니면 `REJECTED`. 본문 전송 뒤(DATA 354 이후) 4xx는
`RETRYABLE`, 5xx는 `REJECTED`, 응답이 유실되면 `UNCERTAIN`. 본문 끝에 250을 받아야 `ACCEPTED`이며 그 뒤의 QUIT·종료 오류는 결과를 바꾸지 않는다.
`providerRetryAt`은 SMTP에서 항상 비어 있고 어댑터 안에서 재전송하지 않는다(at-least-once는 outbox가 맡는다).
STARTTLS를 광고한 서버의 `454`(TLS 일시 사용 불가), STARTTLS 응답 전 연결 단절·시간 초과는 `RETRYABLE`이며, 평문 AUTH·발송으로 전환하지 않는다.
`AUTH_CONFIG_ERROR`도 기존 outbox의 제한된 재시도 정책을 따른다. 여기서 새 재시도 정책이나 운영 알림을 구현하지 않는다(관측성 보완은 #21).

**전체 제한 시간 — A안(호출 격리).** provider 인스턴스당 SMTP 실행 스레드 하나와 감시 스레드 하나를 사용한다. 준비·DNS·연결·발송·정리를 단일 실행
슬롯으로 관리하며 대기열은 없다. `send`는 호출 시작부터 하나의 `provider-timeout`(기본 60s) 예산만 기다린다. 기한이 지나거나 종료를 요청하면 결과를
확정하고 원시 소켓을 닫고 interrupt를 요청한다. 본문 전은 `RETRYABLE`, 본문 전송 이후 수락 여부 불명은 `UNCERTAIN`이다. 제한 시간 내 명시적 수락을
먼저 확정했다면 종료 중 시간 초과로 그 결과를 바꾸지 않는다. 시간 초과가 먼저 확정되면 뒤늦은 결과가 outbox를 덮어쓰지 않는다.

**호출 반환 ≠ DNS 실제 종료.** JDK 동기 해석은 interrupt를 무시할 수 있다. 서버 주소 조회(Jakarta Mail 사전 조회·Angus 소켓 연결)와 EHLO 로컬 이름의
정방향·역방향 조회 모두 발송 스레드 안에 격리한다. 호출자는 반환하지만 멈춘 작업과 그 메모리(메시지·토큰 참조 포함)는 남을 수 있다. Future 완료나 취소로
슬롯을 풀지 않으며, 실제 작업·정리가 끝날 때만 재사용한다. 해석이 늦게 돌아와도 닫힌 소켓과 단계 검사로 새로운 연결·발송을 막는다. 이미 서버가 수신한 메일을
회수할 수 있다는 뜻은 아니다. JVM 스케줄링·GC 정지까지 포함한 실시간 마감 보장은 아니다.

**장애 영향.** 슬롯이 점유된 동안 추가 호출은 즉시 `RETRYABLE`이며 대체 실행기를 만들지 않는다. 상한은 provider 인스턴스당 하나이고 여러 인스턴스 전체의
상한은 아니다. worker는 이미 선점한 시도이므로 이 결과도 시도 횟수를 소비하고 기존 backoff·최대 시도·기한 정책을 따른다. DNS가 영구 정지하면 메일 발송이
계속 불가해 프로세스 재시작이 필요할 수 있다. 요청 API의 접수 성공은 발송 가능의 증명이 아니며 최대 시도 소진·기한 초과로 종결된 작업은 자동 복구되지 않는다.
정리 전까지 새 작업을 거부하는 안전성과 계속 발송 가능한 가용성을 혼동하지 않는다. 실제 DNS 작업 종료 보장이 필요하다면 별도 해석기 설계 승인이 필요하다.

**종료.** context 종료 시 스케줄러가 먼저 정지한다: 새 주기 예약 중단 → 진행 중 작업을 `shutdown-wait`까지 대기 → 넘기면 interrupt로 다음 후보 선점을 막고
진행 중 SMTP 연결 중단(`abortInFlight`) 요청 → 같은 시간만큼 스케줄러 실행기 종료를 다시 확인. 확인되면 WARN, 확인되지 않으면 ERROR로 기록하고 실행기를
보존해 종료 전 재시작을 거부한다. 스케줄러 실행기 종료를 provider DNS 작업 종료로 해석하지 않는다.
끊긴 호출은 본문 전송 전이면 `RETRYABLE`, 본문 전송 뒤면 `UNCERTAIN`으로 outbox에 기록된다(worker가 상태 갱신 전에 끊기면 가시성 만료 뒤 재선점). 그 뒤 어댑터
`close()`가 새 호출을 거부하고 남은 연결을 끊고 감시 스레드(최대 1s)·발송 스레드(최대 5s)의 실제 종료를 확인한다. 남은 스레드가 있으면 미종료로 기록한다.
daemon 설정은 JVM 종료를 붙잡지 않는 선택일 뿐 작업 종료의 증거가 아니다.

**로그.** worker·스케줄러·어댑터 로그에는 작업 id·용도·상태·시도 횟수·단계·분류값·SMTP 응답 코드·요청 식별자만 남는다. 수신 주소·토큰·자격증명·서버 응답 원문·예외 message는
남기지 않는다. Angus Mail의 디버그·프로토콜 로그(JUL 로거 `org.eclipse.angus.mail.smtp`와 `.protocol`, `FINE` 이하)는 EHLO·RCPT TO·본문(토큰 포함)을 그대로
찍는다. Boot는 JUL을 SLF4J로 잇기 때문에 `logging.level`이나 JUL 설정으로 그 로거를 `DEBUG`/`TRACE`로 열면 애플리케이션 로그에 실리는데, 어댑터가 그 두 JUL 로거의
레벨을 `INFO`로 고정하고 매 호출 전에 다시 고정해 막는다(두 레벨을 모두 연 상태에서 SLF4J 브리지를 통해 실측). `mail.debug`(System.out 출력)는 어댑터가 끈다. 이 보장은
그 두 로거 이름에 한하며, 다른 JUL 핸들러를 직접 붙이는 코드는 이 범위 밖이다.

**테스트.** SMTP 어댑터는 루프백의 대본형 서버(`com.itda.support.smtp`)에 진짜 소켓·진짜 TLS로 붙어 검증한다. TLS 자료는 실행마다 `keytool`로 임시 생성하고,
테스트 인증서만 신뢰하는 `SSLContext`를 JVM 기본값으로 잠시 바꿨다가 되돌린다 — 운영 코드는 기본 `SSLSocketFactory`를 그대로 쓰므로 factory 주입 경로가 따로 없다.
저장소에 인증서·키를 두지 않으며 외부 SMTP 서버로 나가는 테스트는 없다. 접수→스케줄러→Redis 토큰→SMTP→`PROVIDER_ACCEPTED` 흐름은 `@RequiresRealRedis`
테스트(`MailDispatchSmtpRedisTest`)가 잇는다.

DNS 회귀는 별도 자식 JVM에만 테스트 resolver SPI를 등록해 외부 DNS 없이 네 조회 경로를 latch로 붙든다. interrupt를 무시해도 호출자가 반환하고,
점유 중 추가 호출·스레드 증가가 없으며 해제 후 늦은 발송 없이 다음 발송이 복구되는지 확인한다. 일반 테스트 JVM·운영 JVM의 DNS 설정은 바꾸지 않는다.
시간 초과/수락의 선후관계는 상태 테스트로, 실제 SMTP 응답·TLS·소켓 중단은 루프백 통합 테스트로 구분해 검증한다.

## DDD 패키지 구조

현재 backend에는 15개 엔티티 테이블이 구현되어 있고 5개 바운디드 컨텍스트로 나눴다 ([docs/product/erd.md](../../docs/product/erd.md)). 제품 ERD에는 후속 구현 대상인 `mail_outbox`가 더해져 총 16개이며, 구현 클래스명과 패키지는 이 문서에서 확정하지 않는다.

```
com.itda
├── global/                  # 공통
│   ├── config/              # Web·Jackson 설정, 실행 환경 프로필 판정(RuntimeEnvironmentProfile)
│   ├── error/, response/
│   ├── security/            # Access Token 공통 인증 경계 — SecurityFilterChain, Bearer 추출, 인증 필터,
│   │                        #   401 진입점, Access JWT 검증·계약값·key ring, JWT kid 형식. 하위 패키지 없음.
│   │                        #   [예정] 역할 기반 인가
│   └── braille/             # BraillePattern — 6비트 셀 shared kernel
├── user/                    # users — 계정·인증. 아래 "user 평면 패키지 탐색" 참고 (하위 패키지 없음)
│   ├── controller/          # HTTP 진입점, 플랫폼 판정, Refresh 쿠키·전달 위치, 요청 filter
│   ├── dto/                 # HTTP Request·Response DTO, JSON 직렬화 지원
│   ├── service/             # 유스케이스·트랜잭션, Command·Result, 사용자 인증 상태 판정, JWT 발급,
│   │                        #   Refresh·action 검증, 비밀번호·유출 조회·메일 연동 계약과 구현
│   ├── repository/          # 저장 계약, JPA·MariaDB 조건부 UPDATE, Redis Lua·키·환경 prefix
│   └── domain/              # User·MailOutboxEntry(엔티티), Email·Username(VO), enum, 불변식
├── lecture/domain/          # Lecture(애그리거트), LectureProblem, Participant, ParticipantId, LectureStatus
├── curriculum/domain/       # CurriculumStage, CurriculumProblem, CurriculumProblemId
├── problem/domain/          # Problem(애그리거트 + ProblemCell), LearningSkill, ProblemSkill, enum 3개
└── learning/domain/         # LearningSession(애그리거트), ProblemAttempt(애그리거트 + AttemptCell·AttemptSkillResult),
                             # UserSkillMemory, AssignedProblem, CellSubmission, enum 4개
```

`user/`의 코드는 위 다섯 패키지에만 있고 `com.itda.user` 루트에는 클래스를 두지 않는다.
비밀번호 경로가 의존 방향의 예시다 — 정책(`domain`) → 새 비밀번호 처리와 Argon2id·유출 조회 구현(`service`) → 가입 저장(`repository`).

### 규칙

1. `domain`은 Spring/JPA 외 어떤 계층에도 의존하지 않는다 (JPA 애너테이션은 실용적 타협).
2. `user`의 Repository **인터페이스와 구현체는 모두 `user/repository`에** 둔다. `domain`은 Repository를 참조하지 않는다. 다른 컨텍스트는 아직 Repository가 없으며 배치는 그 구현 이슈에서 정한다.
3. 엔티티에 setter를 만들지 않는다. 상태 변경은 의미 있는 도메인 메서드로.
4. HTTP Request/Response DTO(`user/dto`)와 Service의 Command/Result(`user/service`)를 분리한다.
5. 시각은 엔티티가 스스로 읽지 않고 인자로 받는다. ERD가 서버 영속 시각을 UTC DB 시각으로 못박고 자동 `ON UPDATE`를 금지하므로,
   한 트랜잭션의 여러 시각이 어긋나면 안 되고 테스트도 결정적이어야 한다.

### user 평면 패키지 탐색

새 최상위 `auth` 패키지를 만들지 않고 `com.itda.user` 안의 다섯 평면 패키지(`controller`·`dto`·`service`·`repository`·`domain`)로 구현한다.
다섯 패키지 아래와 `global/security` 아래에는 하위 패키지를 두지 않는다. 패키지별 책임과 허용·금지 의존 방향의 원본은
[backend 코드 컨벤션 「user 평면 패키지」](../../docs/conventions/code/backend.md#user-평면-패키지)이며, 이 절은 코드를 찾는 기준과 경계의 이유를 적는다.

| 찾는 것 | 패키지 | 판단 기준 |
| --- | --- | --- |
| URL·HTTP 상태·응답 헤더·쿠키·`X-Client-Platform` | `user/controller` | HTTP 요청·응답에서만 의미가 있다 |
| 요청·응답 body의 모양과 JSON 변환 | `user/dto` | 외부 JSON 계약이다 |
| 처리 순서·트랜잭션·오류 code 결정, JWT·비밀번호·메일 연동 | `user/service` | 유스케이스가 호출 순서를 정한다 |
| SQL·조건부 UPDATE·Redis 키·Lua 실행 | `user/repository` | 저장소가 원자성과 결과를 정한다 |
| 상태 전이·정규화·불변식 | `user/domain` | 저장·전송과 무관한 계정 규칙이다 |
| Bearer 추출·보호 경로·401 응답·Access JWT 검증과 계약값, JWT kid 형식 | `global/security` | 모든 보호 API와 세 토큰 설정에 공통이다 |
| 실행 환경 프로필(`local`·`test`·`prod`) 판정 | `global/config` (`RuntimeEnvironmentProfile`) | Access issuer와 Redis key prefix가 같은 규칙을 쓴다 |

기능별 시작점은 다음과 같다. `—`는 그 기능에 해당 패키지의 클래스가 없다는 뜻이다. 모든 기능이 `user/domain`의
`User`·`Email`·`Username`·`PasswordPolicy`·`MailOutboxEntry`·`AccountActionPurpose`를 함께 쓴다.

| 기능 | controller | dto | service | repository |
| --- | --- | --- | --- | --- |
| 회원가입·내 정보 | `UserController` | `UserRegistrationRequest`, `UserSummaryResponse` | `UserRegistrationService`, `UserSummaryQueryService`, `NewPasswordService` | `UserRepository`, `MailOutboxRepository` |
| 로그인 | `AuthSessionController` | `LoginRequest`, `WebAuthSessionResponse`, `MobileAuthSessionResponse` | `LoginService`, `PasswordVerificationService`, `DecoyPasswordHash` | `UserRepository`, `RefreshTokenStore` |
| Access Token 갱신 | `AccessTokenController` | `RefreshTokenRequest`, 인증 응답 DTO | `AccessTokenRefreshService` | `RefreshTokenStore`, `UserRepository` |
| 로그아웃 | `AuthSessionRevocationController` | `RefreshTokenRequest` | `AuthSessionRevocationService` | `RefreshTokenStore` |
| 플랫폼 판정·Refresh 전달 | `ClientPlatform`, `ClientPlatformFilter`, `RefreshTokenDelivery`, `RefreshTokenCookie`, `AuthSessionResponses` | — | — | — |
| 이메일 인증 요청·완료 | `EmailVerificationController` | `EmailVerificationMailRequest`, `EmailVerificationCompletionRequest` | `EmailVerificationRequestService`, `EmailVerificationService`, `MailOutboxEnqueueService` | `ActionTokenStore`, `MailOutboxRepository`, `UserRepository` |
| 회원 탈퇴 | `UserController` | `UserWithdrawalRequest` | `UserWithdrawalService`, `PasswordVerificationService` | `UserRepository`, `RefreshTokenStore` |
| 비밀번호 재설정 요청·완료 | `PasswordResetController` | `PasswordResetMailRequest`, `PasswordResetCompletionRequest` | `PasswordResetRequestService`, `PasswordResetService`, `MailOutboxEnqueueService`, `NewPasswordService` | `MailOutboxRepository`, `ActionTokenStore`, `UserRepository`, `RefreshTokenStore` |
| 메일 발송 worker | — | — | `MailDispatchWorker`, `MailDispatchPolicy`, `MailProvider` | `MailOutboxRepository`, `ActionTokenStore` |
| 비밀번호 해시·유출 조회 | — | — | `PasswordHasher`, `CompromisedPasswordChecker`와 Argon2id·HIBP 구현 | — |
| Access JWT 발급 | — | — | `AccessTokenIssuer`, `NimbusAccessTokenIssuer` | — |
| Refresh·action JWT 발급·검증과 key ring | — | — | 용도별 발급기·검증기, key ring, 설정 | — |
| Redis 키·환경 prefix·Lua 실행 | — | — | — | `AuthRedisKeys`, `AuthRedisProperties`, `LuaRefreshTokenStore`, `LuaActionTokenStore` |
| 보호 API Access 인증 | — (`global/security` filter) | — | `AccessAuthenticationService` | `UserRepository` |

**계약 배치.** 저장 계약(`UserRepository`·`MailOutboxRepository`·`RefreshTokenStore`·`ActionTokenStore`)과 그 입력·결과 타입은
구현과 함께 `user/repository`에 둔다. JWT·비밀번호 해시·유출 조회·메일 제공자처럼 바깥에 요청하는 기능의 계약은
`user/service`에 두고 기술 구현도 같은 패키지에 둔다. 예외는 보호 API가 쓰는 Access JWT 검증 계약(`AccessTokenVerifier`)으로
`global/security`에 둔다. **`domain`은 이 계약들을 참조하지 않는다** — 호출 순서와 트랜잭션 경계를 정하는 것은 `service`다.
Repository는 Service의 정책 타입을 받지 않는다 — 메일 선점 후보 조회의 시도 상한은 `MailDispatchWorker`가 `MailDispatchPolicy`에서
읽어 인자로 넘긴다.

여러 패키지가 함께 쓰는 규칙은 한 곳에만 둔다. Access JWT의 서명 알고리즘·`typ`·`aud`·유효기간은 `global/security`의
`AccessJwtContract`에 두고 발급기(`user/service`의 `NimbusAccessTokenIssuer`)와 검증기(`global/security`의
`NimbusAccessTokenVerifier`)가 함께 읽는다. 서명 key `kid` 형식은 `global/security`의 `JwtKeyContract`가 검사하며 Access·Refresh·action
설정과 key ring이 같은 규칙을 쓴다 — 형식 규칙만 공유할 뿐 Refresh·action token의 발급·검증은 `user/service`에 있다.
실행 환경 프로필 판정은 `global/config`의 `RuntimeEnvironmentProfile` 하나가 맡고, Access issuer 검사와 Redis key prefix 검사가
서로의 설정을 참조하지 않고 이것을 쓴다. 여러 패키지의 테스트가 함께 쓰는 fixture(테스트 key·stub·Redis 테스트 기반 클래스)는
test source의 `com.itda.support.*`에 둔다.

**인증 경계.** `global/security`는 Access Token 기반 보호 API의 공통 인증과 역할 인가를 담당하며, Access JWT 문자열의
서명·claim 검증과 그 계약값·key ring·설정도 여기에 둔다. 사용자 인증 상태 판정(존재·`status`·이메일 인증·`auth_version`)은
계정 도메인 규칙이므로 `user/service`의 `AccessAuthenticationService`가 맡고, `global/security`는 그 결과를 `SecurityContext`와
HTTP 응답으로 옮긴다. 로그인·토큰 갱신·이메일 인증·비밀번호 복구의 자격 증명 검증, Access 발급, Refresh·action token,
메일은 `user`에 남기고 `global`로 옮기지 않는다.

```text
보호 API 요청
  → global.security.AccessTokenAuthenticationFilter
      → BearerTokenExtractor                    Authorization 헤더 → 토큰 원문
      → user.service.AccessAuthenticationService
          → global.security.AccessTokenVerifier  서명·alg·typ·kid·iss·aud·exp·claim
          → user.repository.UserRepository       존재·status·이메일 인증·auth_version
      → Authenticated: SecurityContext ← AuthenticatedUser
        Rejected:      인증 없이 통과 → AuthorizationFilter → AccessAuthenticationEntryPoint (401)
        Unavailable:   503 AUTH_STATE_UNAVAILABLE
```

**보호 경로는 chain을 나눠 하나씩 연다.** `SecurityFilterChain`이 둘이다. `@Order(1)` chain은
`securityMatcher`로 보호 경로만 집어(현재는 `GET /api/v1/users/me`와 `POST /api/v1/users/me/withdrawals`) Access 인증 필터와
`authenticated()`를 걸고, `@Order(2)` chain이 나머지를 `anyRequest().permitAll()`로 받는다.
전체를 `authenticated()`로 막지 않는 이유는 그러면 존재하지 않는 경로가 `404 ENDPOINT_NOT_FOUND` 대신
401이 되어 [API 컨벤션](../../docs/conventions/code/api.md)의 공통 오류 계약이 깨지기 때문이다.
`securityMatcher`에 method까지 넣는 것도 같은 이유다 — 넣지 않으면 `DELETE /api/v1/users/me`가 405 대신 401이 된다.
**보호 matcher 추가는 각 업무 API 구현 이슈가 자기 경로에 대해 명시적으로 한다.**

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
`ACTIVE`인 토큰이 남는다. 새 `jti`가 이미 있다는 `ALREADY_EXISTS`는 성격이 다르다 — Service(`MailDispatchWorker`)가 **새 토큰을 다시 발급해** 최대 3회
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
선택적으로 받는다(`MailProvider.SendOutcome`). 그 시각이 있으면 내부 backoff 상한으로 앞당기지 않고 그대로 쓰며, 기한 이상이면 `DELIVERY_DEADLINE_EXCEEDED`로
종결한다. 요청 식별자는 batch가 아니라 **처리 시도마다** 새로 만들고 끝나면 이전 값으로 되돌린다. 한 작업에서 예상하지 못한 예외가 나도 batch의 뒤 작업은
계속 처리하며, 그 작업은 가시성 만료 뒤 다시 선점된다. 예외 message는 로그에 남기지 않는다 — 제공자 client나 JDBC 예외에는 수신 주소·토큰·SQL이
들어갈 수 있다.

ERD 3.5절의 CHECK 네 개(`attempt_count >= 0`, `delivery_deadline_at > created_at`, PENDING·종결 상태의 컬럼 조합)는
`@CheckConstraint`로 스키마에 만든다. 상태 전이가 엔티티를 거치지 않는 조건부 UPDATE라 Java 불변식만으로는 부족하기 때문이다. 다만 `ddl-auto: validate`는
테이블·컬럼·타입까지만 보므로 **운영 스키마에 이 제약이 있다는 근거가 되지 않는다** — 배포 시 `information_schema.CHECK_CONSTRAINTS`로 따로
확인한다.

**실제 메일 제공자 adapter는 `SmtpMailProvider`다.** 운영 코드에는 발송 성공을 가장하는 no-op adapter를 두지 않는다.
발송 실행은 기본값이 비활성(`MAIL_DISPATCH_ENABLED=false`)이며, 이때는 outbox 접수만 되고 메일은 나가지 않는다.
`MAIL_DISPATCH_ENABLED=true`이면 `MailDispatchConfiguration.DispatchEnabled`에서 **provider·worker·scheduler를 함께** 조립한다.
필수 설정이 유효해야 기동하며, 첫 poll은 기동 직후 실행되므로 처리 가능한 대기 작업이 있으면 실제 발송이 시작될 수 있다.
상세 설정·시간 제한·종료 정책은 [계정 메일 발송 (SMTP outbox)](#계정-메일-발송-smtp-outbox) 절을 따른다.
실제 외부 메일 수신 검증·운영 readiness·운영 관측 보완은 이 구현과 별도로 확인해야 한다.
`PROVIDER_ACCEPTED`는 **제공자가 접수했다**는 뜻이며 수신함 도달을 뜻하지 않는다.
worker가 제공자 호출 뒤 DB 갱신에 실패하면 같은 메일이 다시 나갈 수 있다 — 이 구조는 at-least-once이고 exactly-once가
아니다.

**`mail_outbox` 배포 경계.** `prod`는 `ddl-auto: validate`라서 이 테이블이 운영 스키마에 먼저 있어야 기동한다.
`local`(`update`)·`test`(`create-drop`)는 자동 반영된다.

**`users.auth_version` 배포 경계.** `prod`는 `ddl-auto: validate`라서 이 컬럼이 운영 스키마에 먼저 있어야 기동한다.
`local`(`update`)·`test`(`create-drop`)는 자동 반영된다.

**`users.row_version` 배포 경계.** `User` 엔티티가 ERD에 확정된 `users.row_version`을 `@Version`으로 매핑한다.
`local`(`update`)은 누락된 컬럼을 추가할 수 있지만 `prod`(`validate`)는 컬럼을 만들지 않는다.
운영 스키마에 `users.row_version BIGINT NOT NULL`이 먼저 있지 않으면 backend는 `SchemaManagementException`으로
**기동하지 않는다**. `mail_outbox`와 마찬가지로 release 배포 전에 통제된 스키마 적용 절차가 필요하며,
구체적인 migration·배포 방식은 별도 DB/Infra 작업에서 정한다.

**플랫폼 경계.** `user/service`는 플랫폼 중립적인 인증 결과를 반환하고, `user/controller`가 WEB은 HttpOnly 쿠키로
MOBILE은 응답 body로 변환한다. WEB/MOBILE별 Controller를 복제하지 않는다.
JWT 발급(`user/service`)과 Redis 저장(`user/repository`)은 WEB/MOBILE 공통이다.

**클라이언트 책임.** 웹 쿠키 저장소와 모바일 OS 보안 저장소(Android Keystore·iOS Keychain)는
서버 코드가 아니라 클라이언트 영역이다. 서버 `user/controller`는 전달 방식만 결정하며
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
