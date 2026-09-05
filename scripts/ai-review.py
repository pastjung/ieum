"""MR AI 코드리뷰 — GitLab diff를 GMS(LLM)로 리뷰해 MR 코멘트로 남긴다.

Jenkins MR 파이프라인이 python:3.12-slim 컨테이너에 stdin으로 주입해 실행한다.
표준 라이브러리만 사용한다 (pip 설치 불필요).

환경변수:
  GITLAB_URL, PROJECT_PATH, MR_IID, GITLAB_TOKEN  — GitLab 접근
  GMS_API_KEY, GMS_URI, GMS_MODEL                 — LLM 호출
실패 시 비정상 종료하지만, 파이프라인이 catchError로 감싸므로 빌드는 실패하지 않는다.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

GITLAB = os.environ["GITLAB_URL"].rstrip("/")
PROJECT = urllib.parse.quote(os.environ["PROJECT_PATH"], safe="")
MR_IID = os.environ["MR_IID"]
GL_TOKEN = os.environ["GITLAB_TOKEN"]

MARKER = "<!-- itda-ai-review -->"          # 기존 리뷰 코멘트를 찾아 갱신하기 위한 숨은 표식
SKIP_PATTERNS = ("package-lock.json", ".lock", "dist/", ".min.", ".svg", ".png", ".jpg", ".ico")
PER_FILE_LIMIT = 4000                        # 파일당 diff 글자 수 상한
TOTAL_LIMIT = 16000                          # 전체 diff 글자 수 상한 (초과분은 생략 표시)


def gitlab(method: str, path: str, body: dict | None = None):
    req = urllib.request.Request(
        f"{GITLAB}/api/v4{path}",
        method=method,
        headers={"PRIVATE-TOKEN": GL_TOKEN, "Content-Type": "application/json"},
        data=json.dumps(body).encode() if body is not None else None,
    )
    with urllib.request.urlopen(req, timeout=60) as res:
        return json.loads(res.read() or "null")


def collect_diff() -> str:
    diffs = gitlab("GET", f"/projects/{PROJECT}/merge_requests/{MR_IID}/diffs?per_page=100")
    parts, total, skipped = [], 0, []
    for d in diffs:
        path = d.get("new_path") or d.get("old_path") or ""
        if d.get("generated_file") or any(p in path for p in SKIP_PATTERNS):
            skipped.append(path)
            continue
        text = d.get("diff") or ""
        if len(text) > PER_FILE_LIMIT:
            text = text[:PER_FILE_LIMIT] + "\n... (파일 diff 일부 생략)\n"
        chunk = f"--- {path} ---\n{text}"
        if total + len(chunk) > TOTAL_LIMIT:
            parts.append("\n... (전체 diff가 길어 이후 파일 생략)\n")
            break
        parts.append(chunk)
        total += len(chunk)
    if skipped:
        parts.append(f"\n(리뷰 제외: {', '.join(skipped[:10])})")
    return "\n".join(parts)


def review(diff: str) -> str:
    system = (
        "당신은 이 팀의 시니어 코드 리뷰어다. 아래 Merge Request diff를 리뷰하라.\n"
        "팀 규칙: DDD 계층 구조(domain은 프레임워크 비의존, Repository 인터페이스는 domain에), "
        "엔티티에 setter 금지, presentation DTO와 application Command 분리.\n"
        "출력 형식(한국어, 마크다운):\n"
        "## 요약 — 이 변경이 무엇을 하는지 1~2문장\n"
        "## 지적 사항 — 심각도(🔴버그 가능/🟡개선 권장/🔵취향) 순으로, `파일명` 인용과 함께. 없으면 '없음'\n"
        "## 좋은 점 — 1~2개\n"
        "규칙: diff에 보이는 것만 근거로 삼고 추측은 추측이라고 표시한다. 사소한 스타일 지적은 3개 이하로 제한한다."
    )
    body = {
        "model": os.environ.get("GMS_MODEL", "gpt-4o"),
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": f"MR diff:\n\n{diff}"},
        ],
        "temperature": 0.2,
        "max_tokens": 1500,
    }
    req = urllib.request.Request(
        os.environ["GMS_URI"],
        method="POST",
        headers={"Authorization": f"Bearer {os.environ['GMS_API_KEY']}", "Content-Type": "application/json"},
        data=json.dumps(body).encode(),
    )
    with urllib.request.urlopen(req, timeout=120) as res:
        data = json.loads(res.read())
    return data["choices"][0]["message"]["content"]


def upsert_comment(text: str) -> None:
    body = f"{MARKER}\n### 🤖 AI 코드리뷰 (참고용 — 병합을 막지 않습니다)\n\n{text}"
    notes = gitlab("GET", f"/projects/{PROJECT}/merge_requests/{MR_IID}/notes?per_page=100")
    existing = next((n for n in notes if str(n.get("body", "")).startswith(MARKER)), None)
    if existing:
        gitlab("PUT", f"/projects/{PROJECT}/merge_requests/{MR_IID}/notes/{existing['id']}", {"body": body})
        print(f"기존 리뷰 코멘트 갱신 (note {existing['id']})")
    else:
        gitlab("POST", f"/projects/{PROJECT}/merge_requests/{MR_IID}/notes", {"body": body})
        print("리뷰 코멘트 신규 작성")


def main() -> int:
    diff = collect_diff()
    if not diff.strip():
        print("리뷰할 diff가 없어 종료")
        return 0
    print(f"diff {len(diff)}자 수집, GMS 호출...")
    upsert_comment(review(diff))
    return 0


if __name__ == "__main__":
    sys.exit(main())
