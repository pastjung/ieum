#!/bin/bash
# Stop hook — 턴을 끝내기 전에 변경된 파일의 린트를 검사한다.
# 실패 상태면 종료를 차단(exit 2)하고 실패 내용을 모델에게 전달해 계속 고치게 한다.
# 빠른 검사만 수행한다 (테스트·빌드는 /verify와 CI 담당). 3회 차단 후에는 경고와 함께 양보한다.

PY=$(command -v python3 || command -v python) || exit 0
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
COUNT_FILE="${TMPDIR:-/tmp}/itda-verify-gate-count"

count=$(cat "$COUNT_FILE" 2>/dev/null || echo 0)
if [ "$count" -ge 3 ]; then
  rm -f "$COUNT_FILE"
  echo "[verify-gate] 3회 차단 후 양보한다. 린트 오류가 남아 있으니 사용자에게 반드시 보고하라." >&2
  exit 0
fi

# 이 턴에서 변경된 파일 (staged + unstaged + untracked)
changed=$(cd "$ROOT" && git status --porcelain | sed 's/^...//' | sed 's/^"//;s/"$//')
[ -z "$changed" ] && rm -f "$COUNT_FILE" && exit 0

fails=""

ai_files=$(echo "$changed" | grep -E '^apps/ai/.*\.py$' || true)
if [ -n "$ai_files" ]; then
  RUFF=$(command -v ruff || true)
  [ -z "$RUFF" ] && [ -f "$ROOT/apps/ai/.venv/Scripts/ruff.exe" ] && RUFF="$ROOT/apps/ai/.venv/Scripts/ruff.exe"
  if [ -n "$RUFF" ]; then
    out=$(cd "$ROOT/apps/ai" && echo "$ai_files" | sed 's|^apps/ai/||' | xargs "$RUFF" check --quiet 2>&1) || fails="$fails
[ai ruff]
$out"
  fi
fi

for app in web mobile; do
  files=$(echo "$changed" | grep -E "^apps/$app/.*\.(ts|tsx|js|jsx)$" | grep -v node_modules || true)
  if [ -n "$files" ]; then
    out=$(cd "$ROOT/apps/$app" && echo "$files" | sed "s|^apps/$app/||" | xargs npx --no-install eslint 2>&1) || fails="$fails
[$app eslint]
$out"
  fi
done

if [ -n "$fails" ]; then
  echo $((count+1)) > "$COUNT_FILE"
  echo "[verify-gate] 변경 파일에 린트 오류가 있어 턴을 끝낼 수 없다. 지금 고쳐라:" >&2
  echo "$fails" | head -40 >&2
  exit 2
fi

rm -f "$COUNT_FILE"
exit 0
