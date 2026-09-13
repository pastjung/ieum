# ---------- base: 의존성 레이어 (dev와 builder가 공유) ----------
FROM eclipse-temurin:25-jdk-alpine AS base

WORKDIR /workspace

# 빌드 스크립트만 먼저 복사해 소스 변경 시 의존성 재다운로드를 피한다
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# ---------- redis: 통합 테스트가 쓸 실제 Redis 바이너리 공급 ----------
# 인증 상태 저장소에는 인메모리 대체재가 없다. Lua 스크립트의 원자성·EVALSHA fallback·TTL 갱신은
# 진짜 Redis에서만 증명되므로, 문서 기준 버전(docs/product/erd.md 3.1.1·7절)을 test 스테이지 안에서 띄운다.
# CI에는 Docker 소켓이 없어 별도 컨테이너를 띄울 수 없다 — 그래서 바이너리를 복사해 같은 컨테이너에서 실행한다.
# digest: sha256:30abb90e62f14b737010746def3ba99cc79fe19dcdb3d37b41f21fc62e7da19d
FROM redis:8.2.9-alpine AS redis

# ---------- test: MR 검증 파이프라인용 (docker build --target test) ----------
# BuildKit 캐시 마운트로 Gradle 캐시를 빌드 간 재사용한다.
FROM base AS test

# redis-server가 링크하는 libstdc++는 temurin alpine 이미지에 없다. 이것만 추가한다.
RUN apk add --no-cache libstdc++
COPY --from=redis /usr/local/bin/redis-server /usr/local/bin/redis-server
COPY --from=redis /usr/local/bin/redis-cli /usr/local/bin/redis-cli

COPY src src

# 실제 Redis 통합 테스트를 건너뛰면 빌드를 실패시킨다. 로컬에서는 Redis가 없으면 건너뛰지만,
# 검증 파이프라인에서까지 조용히 건너뛰면 "테스트가 통과했다"가 아무것도 보장하지 않게 된다.
ENV ITDA_REQUIRE_REAL_REDIS=true

# 영속화 없이(RDB·AOF 끔) 루프백에만 바인딩한다. 버전·PING 확인이 실패하면 Gradle까지 가지 않는다.
RUN --mount=type=cache,target=/root/.gradle set -e; \
    redis-server --version | grep -q 'v=8.2.9'; \
    redis-server --daemonize yes --bind 127.0.0.1 --port 6379 --save '' --appendonly no; \
    for i in $(seq 1 30); do \
        if redis-cli ping 2>/dev/null | grep -q PONG; then break; fi; \
        sleep 1; \
    done; \
    redis-cli ping | grep -q PONG; \
    redis-cli info server | tr -d '\r' | grep -qx 'redis_version:8.2.9'; \
    ./gradlew spotlessCheck test --no-daemon; \
    for report in build/test-results/test/TEST-*RedisTest.xml; do \
        grep -q 'skipped="0"' "$report"; \
    done

# ---------- dev: 로컬 개발용 (루트 docker-compose.yml이 target: dev로 사용) ----------
# 소스는 compose가 볼륨 마운트한다. build --continuous가 변경을 감지해 재컴파일하고,
# bootRun에 올라간 devtools가 바뀐 클래스를 감지해 애플리케이션만 빠르게 재시작한다.
FROM base AS dev

# 기동 시점의 소스 (이후 변경은 compose watch sync가 반영)
COPY src src

CMD ["sh", "-c", "./gradlew build --continuous -x test --no-daemon --quiet & exec ./gradlew bootRun --no-daemon"]

# ---------- builder: 운영 jar 빌드 ----------
FROM base AS builder

COPY src src

# 테스트는 MR 검증 파이프라인에서 수행하므로 이미지 빌드에서는 제외
RUN ./gradlew bootJar --no-daemon -x test \
    && cp build/libs/*.jar app.jar

# ---------- runtime: 운영 이미지 (기본 빌드 대상) ----------
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/app.jar app.jar

USER spring:spring

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
