#!/bin/bash
# PreToolUse(Bash) — 셸 명령을 통한 비밀 파일 접근을 차단한다 (Read 도구 우회 방지).
# exit 2 = 명령 실행 거부 + stderr가 모델에게 전달. 규칙: docs/conventions/quality/security.md

PY=$(command -v python3 || command -v python) || exit 0
CMD=$("$PY" -c "import sys,json;print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null)
[ -z "$CMD" ] && exit 0

# .env.example 계열은 허용 대상이므로 판정 전에 제거
FILTERED=$(echo "$CMD" | sed 's/\.env[.a-zA-Z0-9_-]*\.example//g; s/\.env\.example//g')

if echo "$FILTERED" | grep -qE '(^|[^a-zA-Z0-9_.-])\.env([^a-zA-Z0-9_-]|$)|\.env\.[a-zA-Z]|[a-zA-Z0-9_-]+\.(pem|key)([^a-zA-Z0-9]|$)'; then
  echo "차단됨: 이 명령은 비밀 파일(.env/.pem/.key)에 접근한다. AI는 비밀 파일을 읽거나 수정하지 않는다." >&2
  echo "필요한 것이 '어떤 키가 존재하는지'라면 .env.example을 읽어라. (docs/conventions/quality/security.md)" >&2
  exit 2
fi
exit 0
