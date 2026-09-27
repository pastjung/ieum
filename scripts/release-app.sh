#!/usr/bin/env bash
# =========================================================================
# 학습자 앱(APK) 릴리스 — 사람이 PC(Git Bash)에서 실행한다 (docs/infra/deploy.md 9절)
#
# 사용법: scripts/release-app.sh "<변경 내용 한두 줄>"
# 환경변수:
#   GITLAB_TOKEN      GitLab 개인 액세스 토큰(api scope). 저장소·파일에 두지 않는다
#   IEUM_DEPLOY_KEY   EC2 접속 pem 파일 경로 (저장소 밖)
#   IEUM_DEPLOY_HOST  (선택) 기본 ubuntu@i-eum.site
# 전제: HEAD에 app-v<app.json version> 태그가 있고 origin에도 같은 커밋으로 올라가 있다.
#       front/apps/mobile/.env.production, ~/.gradle/gradle.properties의 IEUM_UPLOAD_* 서명 속성.
#
# 순서: 확인 → 테스트 → 빌드 → 해시 → latest.json → GitLab Release → 서버 교체
# 실패하면 그 자리에서 멈추고 끝난 단계를 출력한다. 서버 교체가 마지막이므로
# 그 전에 멈추면 학습자가 받는 파일은 바뀌지 않는다.
# =========================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE_DIR="$REPO_ROOT/front/apps/mobile"
APK_BUILT="$MOBILE_DIR/android/app/build/outputs/apk/release/app-release.apk"
GITLAB_API="https://lab.ssafy.com/api/v4"
PROJECT_ID="s15-bigdata-recom-sub1%2FS15P21B201"
PACKAGE_NAME="ieum-android"
PUBLIC_APK_URL="https://i-eum.site/app/ieum.apk"
PUBLIC_LATEST_URL="https://i-eum.site/app/latest.json"
REMOTE_DIR="/home/ubuntu/app/downloads"
DEPLOY_HOST="${IEUM_DEPLOY_HOST:-ubuntu@i-eum.site}"

DONE=()
WORK=""

step_done() {
  DONE+=("$1")
  echo "[release] 완료: $1"
}

on_exit() {
  local code=$?
  if [ "$code" -ne 0 ]; then
    echo "[release] 실패 (종료 코드 $code). 끝난 단계:" >&2
    if [ "${#DONE[@]}" -eq 0 ]; then
      echo "  (없음)" >&2
    else
      printf '  - %s\n' "${DONE[@]}" >&2
    fi
    if [ -n "$WORK" ]; then
      echo "[release] 중간 산출물: $WORK" >&2
    fi
  fi
}
trap on_exit EXIT

fail() {
  echo "[release] $*" >&2
  exit 1
}

# GitLab API 호출. 실패하면 응답 본문을 보여 주고 멈춘다 (토큰은 출력하지 않는다)
gitlab() {
  local out
  if ! out="$(curl --fail-with-body -sS -H "PRIVATE-TOKEN: $GITLAB_TOKEN" "$@")"; then
    echo "[release] GitLab API 실패: $out" >&2
    return 1
  fi
}

# ── 0. 입력 ──────────────────────────────────────────────────────────────
NOTES="${1:-}"
[ -n "$NOTES" ] || fail '사용법: scripts/release-app.sh "<변경 내용 한두 줄>"'
[ -n "${GITLAB_TOKEN:-}" ] || fail "GITLAB_TOKEN 환경변수가 없습니다 (api scope 개인 액세스 토큰)"
if [ -z "${IEUM_DEPLOY_KEY:-}" ] || [ ! -f "$IEUM_DEPLOY_KEY" ]; then
  fail "IEUM_DEPLOY_KEY가 서버 접속 pem 파일을 가리키지 않습니다"
fi
for cmd in git node pnpm npx curl scp ssh sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || fail "$cmd 명령을 찾을 수 없습니다"
done
SSH_OPTS=(-i "$IEUM_DEPLOY_KEY" -o StrictHostKeyChecking=accept-new)

# ── 1. 확인 ──────────────────────────────────────────────────────────────
cd "$REPO_ROOT"
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "커밋하지 않은 변경이 있습니다 (git status)"
VERSION="$(node -p "require('./front/apps/mobile/app.json').expo.version")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "app.json expo.version이 X.Y.Z 형식이 아닙니다: $VERSION"
TAG="app-v$VERSION"
HEAD_TAGS="$(git tag --points-at HEAD)"
grep -qx "$TAG" <<<"$HEAD_TAGS" || fail "HEAD에 $TAG 태그가 없습니다 (app.json version=$VERSION)"
VERSION_CODE="$(node -p "require('./front/apps/mobile/app.json').expo.android.versionCode")"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "app.json expo.android.versionCode가 양의 정수가 아닙니다: $VERSION_CODE"
[ -f "$MOBILE_DIR/.env.production" ] || fail "front/apps/mobile/.env.production이 없습니다 (deploy.md 9.2)"
HEAD_SHA="$(git rev-parse HEAD)"
REMOTE_TAG="$(git ls-remote --tags origin "refs/tags/$TAG" "refs/tags/$TAG^{}")"
grep -q "^$HEAD_SHA" <<<"$REMOTE_TAG" || fail "origin의 $TAG가 HEAD를 가리키지 않습니다 (git push origin $TAG)"
gitlab "$GITLAB_API/projects/$PROJECT_ID" || fail "GITLAB_TOKEN으로 프로젝트를 읽지 못했습니다 (scope·만료 확인)"
# shellcheck disable=SC2029  # REMOTE_DIR은 이 파일의 상수라 클라이언트에서 펼쳐도 안전하다
ssh "${SSH_OPTS[@]}" "$DEPLOY_HOST" test -w "$REMOTE_DIR" \
  || fail "서버 $REMOTE_DIR에 쓸 수 없습니다 (서버 배포 Sync가 한 번 돌았는지 확인)"
step_done "확인 ($TAG, versionCode $VERSION_CODE)"

# ── 2. 테스트 ────────────────────────────────────────────────────────────
(cd "$REPO_ROOT/front" && pnpm --filter @ieum/mobile test && pnpm --filter @ieum/mobile typecheck)
step_done "테스트·타입 검사"

# ── 3. 빌드 ──────────────────────────────────────────────────────────────
(cd "$MOBILE_DIR" && CI=1 NODE_ENV=production npx expo prebuild -p android --clean)
(cd "$MOBILE_DIR/android" && NODE_ENV=production ./gradlew assembleRelease)
[ -f "$APK_BUILT" ] || fail "빌드 산출물이 없습니다: $APK_BUILT"
step_done "release APK 빌드"

# ── 4. 산출물·해시 ───────────────────────────────────────────────────────
WORK="$(mktemp -d)"
APK_NAME="ieum-$VERSION.apk"
cp "$APK_BUILT" "$WORK/$APK_NAME"
SHA256="$(sha256sum "$WORK/$APK_NAME" | cut -d' ' -f1)"
printf '%s  %s\n' "$SHA256" "$APK_NAME" > "$WORK/$APK_NAME.sha256"
printf '%s\n' "$NOTES" > "$WORK/notes.txt"
step_done "SHA-256 $SHA256"

# ── 5. latest.json ───────────────────────────────────────────────────────
REL_VERSION="$VERSION" REL_VERSION_CODE="$VERSION_CODE" \
REL_RELEASED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" REL_SHA256="$SHA256" REL_URL="$PUBLIC_APK_URL" \
node -e '
const fs = require("fs");
const e = process.env;
const notes = fs.readFileSync(0, "utf8").trim();
const body = {
  version: e.REL_VERSION,
  versionCode: Number(e.REL_VERSION_CODE),
  releasedAt: e.REL_RELEASED_AT,
  sha256: e.REL_SHA256,
  url: e.REL_URL,
  notes: notes
};
process.stdout.write(JSON.stringify(body, null, 2) + "\n");
' < "$WORK/notes.txt" > "$WORK/latest.json"
step_done "latest.json 생성"

# ── 6. GitLab: 일반 패키지 업로드 + Release ──────────────────────────────
PKG_URL="$GITLAB_API/projects/$PROJECT_ID/packages/generic/$PACKAGE_NAME/$VERSION"
gitlab --upload-file "$WORK/$APK_NAME" "$PKG_URL/$APK_NAME"
gitlab --upload-file "$WORK/$APK_NAME.sha256" "$PKG_URL/$APK_NAME.sha256"
step_done "GitLab 패키지 업로드 ($PACKAGE_NAME/$VERSION)"

REL_TAG="$TAG" REL_VERSION="$VERSION" REL_VERSION_CODE="$VERSION_CODE" REL_SHA256="$SHA256" \
REL_APK_NAME="$APK_NAME" REL_PKG_URL="$PKG_URL" \
node -e '
const fs = require("fs");
const e = process.env;
const notes = fs.readFileSync(0, "utf8").trim();
const apk = e.REL_PKG_URL + "/" + e.REL_APK_NAME;
const body = {
  tag_name: e.REL_TAG,
  name: "I:EUM app " + e.REL_VERSION,
  description: notes + "\n\n- versionCode: " + e.REL_VERSION_CODE + "\n- SHA-256: " + e.REL_SHA256,
  assets: {
    links: [
      { name: e.REL_APK_NAME, url: apk, link_type: "package" },
      { name: e.REL_APK_NAME + ".sha256", url: apk + ".sha256", link_type: "other" }
    ]
  }
};
process.stdout.write(JSON.stringify(body));
' < "$WORK/notes.txt" > "$WORK/release.json"
gitlab -X POST -H "Content-Type: application/json" --data-binary @- \
  "$GITLAB_API/projects/$PROJECT_ID/releases" < "$WORK/release.json"
step_done "GitLab Release $TAG"

# ── 7. 서버 교체 — 임시 이름으로 올린 뒤 mv (받는 도중 파일이 바뀌지 않게) ──
scp "${SSH_OPTS[@]}" "$WORK/$APK_NAME" "$DEPLOY_HOST:$REMOTE_DIR/ieum.apk.tmp"
scp "${SSH_OPTS[@]}" "$WORK/latest.json" "$DEPLOY_HOST:$REMOTE_DIR/latest.json.tmp"
# shellcheck disable=SC2029  # VERSION·SHA256은 위에서 형식을 검사한 값이라 클라이언트에서 펼쳐도 안전하다
ssh "${SSH_OPTS[@]}" "$DEPLOY_HOST" "bash -s -- $VERSION $SHA256" <<'REMOTE'
set -euo pipefail
new_version="$1"
new_sha="$2"
cd /home/ubuntu/app/downloads
actual="$(sha256sum ieum.apk.tmp | cut -d' ' -f1)"
if [ "$actual" != "$new_sha" ]; then
  echo "[server] 올린 APK의 SHA-256이 다르다 — 교체하지 않는다" >&2
  rm -f ieum.apk.tmp latest.json.tmp
  exit 1
fi
keep=""
if [ -f ieum.apk ] && [ -f latest.json ]; then
  prev="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' latest.json | head -n 1)"
  if [ -n "$prev" ] && [ "$prev" != "$new_version" ]; then
    cp -p ieum.apk "ieum-$prev.apk.tmp"
    mv -f "ieum-$prev.apk.tmp" "ieum-$prev.apk"
    keep="ieum-$prev.apk"
  fi
fi
mv -f ieum.apk.tmp ieum.apk
mv -f latest.json.tmp latest.json
chmod 644 ieum.apk latest.json
if [ -n "$keep" ]; then
  chmod 644 "$keep"
  find . -maxdepth 1 -type f -name 'ieum-*.apk' ! -name "$keep" -print -delete
fi
echo "[server] 교체 완료: ieum.apk=$new_version, 직전 보관: ${keep:-없음}"
REMOTE
step_done "서버 교체 ($REMOTE_DIR)"

LIVE="$(curl -fsS "$PUBLIC_LATEST_URL")" || fail "$PUBLIC_LATEST_URL을 읽지 못했습니다 (nginx·권한 확인: deploy.md 10절)"
grep -q "$SHA256" <<<"$LIVE" || fail "공개된 latest.json의 sha256이 이번 빌드와 다릅니다"
step_done "공개 주소 확인 ($PUBLIC_LATEST_URL)"

rm -rf "$WORK"
WORK=""
echo "[release] $TAG 배포 끝 — 폰에서 https://i-eum.site/app 으로 설치를 확인한다"
