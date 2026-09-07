#!/bin/bash
# gitleaks 실행 래퍼 — 바이너리가 없으면 GitHub 릴리스에서 받아 사용자 캐시에 저장한다.
set -e

VERSION="8.30.1"
CACHE_DIR="$HOME/.cache/gitleaks/$VERSION"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows; EXT=zip;    BIN=gitleaks.exe ;;
  Darwin)               OS=darwin;  EXT=tar.gz; BIN=gitleaks ;;
  *)                    OS=linux;   EXT=tar.gz; BIN=gitleaks ;;
esac

GITLEAKS="$CACHE_DIR/$BIN"

if [ ! -x "$GITLEAKS" ]; then
  echo "[gitleaks] 최초 1회: v$VERSION 바이너리 다운로드 중 (~8MB)..." >&2
  mkdir -p "$CACHE_DIR"
  url="https://github.com/gitleaks/gitleaks/releases/download/v${VERSION}/gitleaks_${VERSION}_${OS}_x64.${EXT}"
  curl -sSL --fail "$url" -o "$CACHE_DIR/pkg.$EXT" || {
    echo "[gitleaks] 다운로드 실패: $url" >&2
    echo "  네트워크를 확인하거나 위 파일을 받아 $CACHE_DIR/$BIN 으로 두세요." >&2
    exit 1
  }
  if [ "$EXT" = "zip" ]; then
    powershell.exe -NoProfile -Command \
      "Expand-Archive -Path '$(cygpath -w "$CACHE_DIR/pkg.zip")' -DestinationPath '$(cygpath -w "$CACHE_DIR")' -Force" >/dev/null
  else
    tar -xzf "$CACHE_DIR/pkg.$EXT" -C "$CACHE_DIR"
  fi
  rm -f "$CACHE_DIR/pkg.$EXT"
  [ -x "$GITLEAKS" ] || chmod +x "$GITLEAKS" 2>/dev/null || true
fi

exec "$GITLEAKS" git --pre-commit --staged --redact --no-banner
