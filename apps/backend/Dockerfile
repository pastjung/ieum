# ---------- base: 의존성 레이어 (dev와 builder가 공유) ----------
FROM eclipse-temurin:25-jdk-alpine AS base

WORKDIR /workspace

# 빌드 스크립트만 먼저 복사해 소스 변경 시 의존성 재다운로드를 피한다
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# ---------- test: MR 검증 파이프라인용 (docker build --target test) ----------
# BuildKit 캐시 마운트로 Gradle 캐시를 빌드 간 재사용한다.
FROM base AS test

COPY src src

RUN --mount=type=cache,target=/root/.gradle ./gradlew test --no-daemon

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
