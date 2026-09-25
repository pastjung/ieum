#!/bin/bash
# PostToolUse(Write|Edit) — 수정된 파일에 자동 포맷을 적용하고, 남은 린트 오류를 모델에게 피드백한다.
# 도구가 없으면 조용히 통과(fail-open). 진짜 오류만 exit 2 + stderr로 전달해 모델이 즉시 고치게 한다.

PY=$(command -v python3 || command -v python) || exit 0
FILE=$("$PY" -c "import sys,json;print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null)
[ -z "$FILE" ] && exit 0

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
REL=${FILE#"$ROOT"/}

feedback() {  # $1 = 린터 출력
  echo "[post-edit-lint] 수정한 파일에 린트 오류가 남아 있다. 지금 고쳐라:" >&2
  echo "$1" >&2
  exit 2
}

case "$REL" in
  apps/ai/*.py)
    RUFF=$(command -v ruff || true)
    [ -z "$RUFF" ] && [ -f "$ROOT/apps/ai/.venv/Scripts/ruff.exe" ] && RUFF="$ROOT/apps/ai/.venv/Scripts/ruff.exe"
    [ -z "$RUFF" ] && exit 0
    (cd "$ROOT/apps/ai" && "$RUFF" format --quiet "$FILE" && "$RUFF" check --fix --quiet "$FILE") >/dev/null 2>&1
    out=$(cd "$ROOT/apps/ai" && "$RUFF" check --quiet "$FILE" 2>&1) || feedback "$out"
    ;;
  apps/backend/*.java)
    (cd "$ROOT/apps/backend" && ./gradlew spotlessApply -q --console=plain) >/dev/null 2>&1 || true
    ;;
  front/apps/web/*.ts|front/apps/web/*.tsx)
    out=$(cd "$ROOT/front/apps/web" && pnpm exec eslint --fix "$FILE" 2>&1) || feedback "$out"
    ;;
  apps/mobile/*.ts|apps/mobile/*.tsx|apps/mobile/*.js|apps/mobile/*.jsx)
    out=$(cd "$ROOT/apps/mobile" && npx --no-install eslint --fix "$FILE" 2>&1) || feedback "$out"
    ;;
esac
exit 0
