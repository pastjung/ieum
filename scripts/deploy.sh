#!/usr/bin/env bash
# =========================================================================
# blue-green 무중단 배포 스크립트 (EC2의 /home/ubuntu/app/scripts/ 에서 실행)
#
# 사용법: ./deploy.sh <service> <tag>
#   service : web | backend | ai
#   tag     : 배포할 이미지 태그 (예: 1.0.0-rc.42-abc1234, v1.0.0)
#
# 동작: 비활성 슬롯에 새 태그 기동 → 헬스체크 → Nginx 포트 전환(reload)
#       이전 슬롯 컨테이너는 즉시 롤백용으로 유지한다 (다음 배포 때 교체됨)
# 롤백: 같은 스크립트에 이전 태그를 주면 된다  예) ./deploy.sh backend v1.0.0
# =========================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # /home/ubuntu/app
COMPOSE="docker compose -f $APP_DIR/docker-compose.prod.yml --env-file $APP_DIR/.env"
NGINX_INC_DIR="$APP_DIR/nginx/inc"

SERVICE="${1:?usage: deploy.sh <web|backend|ai> <tag>}"
TAG="${2:?usage: deploy.sh <web|backend|ai> <tag>}"

# 서비스별 설정: blue포트 green포트 헬스체크경로 태그변수 inc변수
case "$SERVICE" in
  web)     BLUE=3001; GREEN=3002; HEALTH_PATH="/";              TAG_VAR="WEB_TAG";     PORT_VAR="web_port" ;;
  backend) BLUE=8081; GREEN=8082; HEALTH_PATH="/api/v1/health"; TAG_VAR="BACKEND_TAG"; PORT_VAR="backend_port" ;;
  ai)      BLUE=8101; GREEN=8102; HEALTH_PATH="/api/v1/health"; TAG_VAR="AI_TAG";      PORT_VAR="ai_port" ;;
  *) echo "unknown service: $SERVICE" >&2; exit 1 ;;
esac

INC_FILE="$NGINX_INC_DIR/$SERVICE.inc"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-90}"   # ai 모델 로딩이 길어지면 환경변수로 조정

# 1. 현재 활성 슬롯 판별 → 반대 슬롯을 배포 대상으로
CURRENT_PORT="$(grep -oE '[0-9]+' "$INC_FILE" 2>/dev/null || echo "")"
if [ "$CURRENT_PORT" = "$BLUE" ]; then
  TARGET_COLOR="green"; TARGET_PORT=$GREEN
else
  TARGET_COLOR="blue";  TARGET_PORT=$BLUE   # 첫 배포이거나 green 활성 상태
fi
TARGET="$SERVICE-$TARGET_COLOR"
echo "[deploy] $SERVICE: tag=$TAG, 대상 슬롯=$TARGET_COLOR($TARGET_PORT), 현재 활성 포트=${CURRENT_PORT:-없음}"

# 2. .env의 태그 갱신 후 대상 슬롯 기동
sed -i "s|^$TAG_VAR=.*|$TAG_VAR=$TAG|" "$APP_DIR/.env"
$COMPOSE up -d --no-deps "$TARGET"

# 3. 헬스체크 폴링 — 실패 시 새 컨테이너를 내리고 중단 (기존 서비스 무영향)
echo "[deploy] 헬스체크: http://127.0.0.1:$TARGET_PORT$HEALTH_PATH (최대 ${HEALTH_TIMEOUT}s)"
DEADLINE=$((SECONDS + HEALTH_TIMEOUT))
until curl -sf -o /dev/null "http://127.0.0.1:$TARGET_PORT$HEALTH_PATH"; do
  if [ $SECONDS -ge $DEADLINE ]; then
    echo "[deploy] 헬스체크 실패 — $TARGET 을 중단하고 배포를 취소합니다" >&2
    $COMPOSE logs --tail=50 "$TARGET" >&2 || true
    $COMPOSE stop "$TARGET" >/dev/null
    exit 1
  fi
  sleep 3
done
echo "[deploy] 헬스체크 통과"

# 4. Nginx 전환 (reload는 기존 연결을 끊지 않음)
echo "set \$$PORT_VAR $TARGET_PORT;" > "$INC_FILE"
sudo nginx -t
sudo systemctl reload nginx
echo "[deploy] 완료: $SERVICE → $TARGET_COLOR($TARGET_PORT), tag=$TAG"
echo "[deploy] 이전 슬롯은 즉시 롤백용으로 유지됩니다. 즉시 롤백: $INC_FILE 포트를 되돌리고 sudo systemctl reload nginx"
