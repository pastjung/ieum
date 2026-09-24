# 학습자 앱 모노레포(pnpm workspace)의 검증 이미지.
#
# MR 검증 파이프라인이 `docker build --target test front`로 이 파일을 쓴다 (Jenkinsfile.mr).
# 이 파일이 없으면 front/ 를 건드린 모든 MR이 코드 품질과 무관하게 빨간불이 된다.
#
# 운영 이미지는 여기 없다. front/ 에는 앱(Expo)만 있고 웹 서비스는 저장소 루트 apps/web 이
# 자기 Dockerfile로 갖고 있다. Jenkinsfile의 배포 단계가 `--target web front`를 부르는 것은
# 저장소 구조와 어긋난 부분이며, 배포 작업에서 apps/web 을 가리키도록 함께 정리해야 한다.

# ---------- base: 의존성 레이어 ----------
FROM node:22-alpine AS base

WORKDIR /workspace

# corepack이 붙여 주는 pnpm 버전을 lockfile을 만든 버전에 맞춘다. 버전이 다르면
# --frozen-lockfile이 "lockfile이 낡았다"며 실패하거나, 조용히 다른 트리를 만든다.
RUN corepack enable && corepack prepare pnpm@10.34.5 --activate

# ---------- test: MR 검증 파이프라인용 ----------
FROM base AS test

# 워크스페이스 패키지가 늘어날 때마다 고쳐야 하는 매니페스트 개별 COPY는 쓰지 않는다.
# 실제로 이 저장소는 작업 중에 packages/itda 와 packages/api-client 가 새로 생겼고,
# 그때마다 조용히 빠지는 편이 캐시 이득보다 비싸다. 대신 pnpm store를 캐시 마운트로 재사용한다.
COPY . .

RUN --mount=type=cache,target=/pnpm-store \
    pnpm config set store-dir /pnpm-store \
    && pnpm install --frozen-lockfile

RUN pnpm -r typecheck

# `pnpm -r test`만 쓰면 test 스크립트를 가진 패키지가 하나도 없어도 성공한다.
# "테스트가 통과했다"가 아무것도 보장하지 않는 상태를 만들지 않기 위해, 실제 스위트를
# 명시적으로 돌리고 실행 건수가 0이 아닌지 확인한다.
RUN pnpm -r test \
    && pnpm --filter @ieum/mobile exec jest --ci --json --outputFile=/tmp/jest.json > /dev/null \
    && node -e " \
        const r = require('/tmp/jest.json'); \
        if (!r.success) { console.error('테스트 실패'); process.exit(1); } \
        if (!r.numTotalTests || r.numTotalTests < 1) { \
          console.error('테스트가 한 건도 실행되지 않았습니다'); process.exit(1); \
        } \
        console.log('jest 통과: ' + r.numPassedTests + '/' + r.numTotalTests); \
      "
