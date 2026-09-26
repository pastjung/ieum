#!/bin/bash
# PreToolUse(Read|Edit|Write) — AI가 민감 파일을 읽거나 수정하려는 시도를 차단한다.
# exit 2 = 도구 실행이 거부되고 stderr가 모델에게 전달된다. 규칙: docs/conventions/quality/security.md

PY=$(command -v python3 || command -v python) || exit 0
FILE=$("$PY" -c "import sys,json;print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null)
[ -z "$FILE" ] && exit 0

base=$(basename "$FILE")

blocked=""
case "$base" in
  .env|.env.*) [ "${base##*.}" != "example" ] && blocked=".env 계열 비밀 설정" ;;
  *.pem|*.key) blocked="키 파일" ;;
esac

if [ -n "$blocked" ]; then
  echo "차단됨: $FILE 은(는) $blocked 파일이다. AI는 비밀 파일을 읽거나 수정하지 않는다." >&2
  echo "설정 키 추가가 목적이면 .env.example 갱신을 제안하라. (docs/conventions/quality/security.md)" >&2
  exit 2
fi
exit 0
