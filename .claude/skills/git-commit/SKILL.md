---
name: git-commit
description: 변경사항을 팀 컨벤션에 맞는 커밋으로 만든다. 사용자가 커밋을 요청하거나 작업을 마무리해 커밋이 필요할 때 반드시 사용.
---

# 커밋 생성 절차

1. `git status`와 `git diff`로 변경 내용을 파악한다. staged가 없으면 무엇을 스테이징할지 사용자와 확인한다.
2. 서로 다른 목적의 변경이 섞여 있으면 커밋 분리를 제안한다. **기계가 만든 변경(포맷)과 사람이 판단한 변경은 절대 한 커밋에 섞지 않는다.**
3. apps/ 코드가 바뀌었으면 커밋 전에 AGENTS.md의 해당 검증 명령을 통과시킨다 (문서·설정만이면 diff 확인으로 대체).
4. docs/conventions/git/commit-convention.md 형식으로 메시지를 작성한다:
   `type(scope): 한글 요약` — 이슈 연결은 footer `Refs #번호`
5. 커밋한다. pre-commit 훅이 실패하면 지적된 원인을 고치고 다시 커밋한다. `--no-verify` 금지.
