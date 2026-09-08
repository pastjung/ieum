# 학습자 앱(React Native)은 컨테이너로 배포하지 않는다 — 이 Dockerfile은 CI 검증 전용이다.
# 실제 앱 빌드(APK/IPA)는 Android/Xcode 툴체인이 필요하므로 별도 파이프라인에서 다룬다.

# ---------- base: 의존성 ----------
FROM node:22-alpine AS base

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

# ---------- test: MR 검증 파이프라인용 (docker build --target test) ----------
FROM base AS test

COPY . .

# 린트 → 타입체크 → 테스트 (하나라도 실패하면 빌드 실패 = verify ❌)
RUN npm run lint && npx tsc --noEmit && npm test -- --ci
