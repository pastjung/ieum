"""배포 알림용 변경 요약 — GMS로 커밋 로그를 한국어 요약한다.

Jenkinsfile(배포 post)에서 stdin 주입으로 실행:
  docker run --rm -i -e CHANGES -e GMS_API_KEY -e GMS_URI -e GMS_MODEL \
      python:3.12-slim python - < scripts/deploy-summary.py

입력: CHANGES 환경변수 (git log 커밋 제목+본문)
출력: stdout에 요약 불릿 목록. 실패하면 빈 출력 + stderr 로그
      → Jenkinsfile이 커밋 목록으로 폴백하므로 알림은 항상 전송된다.
표준 라이브러리만 사용한다 (pip 설치 없음).
"""
import json
import os
import sys
import urllib.request

changes = os.environ.get("CHANGES", "").strip()
if not changes:
    sys.exit(0)

SYSTEM = (
    "너는 배포 알림 요약가다. 아래 커밋 로그를 읽고 이번 배포에서 무엇이 바뀌었는지 "
    "팀원(비개발자 포함)이 이해할 수 있는 한국어로 요약하라.\n"
    "규칙:\n"
    "- 불릿(•) 3~6개, 각 한 줄\n"
    "- 사용자·운영 관점의 변화를 우선하고, 비슷한 커밋은 하나로 묶는다\n"
    "- 로그에 없는 내용을 추측하거나 과장하지 않는다\n"
    "- 불릿 목록만 출력한다 (머리말·맺음말 금지)"
)

body = {
    "model": os.environ["GMS_MODEL"],
    "messages": [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": changes[:8000]},
    ],
    # GPT-5 계열: max_tokens 대신 max_completion_tokens, temperature 지정 금지
    "max_completion_tokens": 500,
}

req = urllib.request.Request(
    os.environ["GMS_URI"],
    data=json.dumps(body).encode(),
    headers={
        "Authorization": f"Bearer {os.environ['GMS_API_KEY']}",
        "Content-Type": "application/json",
    },
)
try:
    with urllib.request.urlopen(req, timeout=60) as res:
        out = json.load(res)["choices"][0]["message"]["content"].strip()
        print(out)
except Exception as e:  # noqa: BLE001 — 어떤 실패든 폴백으로
    print(f"deploy-summary 실패: {e}", file=sys.stderr)
    sys.exit(0)
