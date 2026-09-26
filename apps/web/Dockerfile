# ---------- build ----------
# 로컬 개발 시 루트 docker-compose.yml이 이 스테이지(target: build)에서 vite dev 서버를 실행한다
FROM node:22-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

RUN npm run build

# ---------- runtime ----------
FROM nginx:stable-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# ---------- test: MR 검증 파이프라인용 (docker build --target test) ----------
# build 스테이지(npm ci + tsc + vite build)가 이미 성공한 위에서 린트만 추가로 검사한다
FROM build AS test

RUN npm run lint
