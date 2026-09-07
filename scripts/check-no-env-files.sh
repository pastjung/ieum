#!/bin/bash
# .env 계열 파일 커밋 차단 — 원본 규칙: docs/conventions/quality/security.md
# .gitignore가 1차로 막지만, git add -f 나 예외 경로로 새는 것을 커밋 시점에 재차단한다.

staged=$(git -c core.quotePath=false diff --cached --name-only --diff-filter=ACMR --)
blocked=$(printf '%s\n' "$staged" | grep -E '(^|/)\.env(\..*)?$' | grep -v '\.example$')

if [ -n "$blocked" ]; then
  {
    echo "[forbid-env] 비밀 설정 파일은 커밋할 수 없습니다:"
    printf '  %s\n' $blocked
    echo "  키 이름만 공유하려면 .env.example을 갱신하세요. (docs/conventions/quality/security.md)"
  } >&2
  exit 1
fi
exit 0
