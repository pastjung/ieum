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
