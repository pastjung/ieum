---
name: mr-draft
description: MR 타이틀과 본문 초안을 완성형으로 만든다. 작업을 마치고 MR을 올릴 준비가 되면 사용. 등록은 사람이 GitLab 웹에서 한다.
---

# MR 초안 작성 절차

1. 대상 브랜치를 판단한다 (docs/conventions/git/branch-strategy.md): 일반 작업 → `dev`, 상위 feat의 하위 작업 → 상위 브랜치, 릴리스 수정 → `release/*`.
2. push가 안 되어 있으면 먼저 `git push -u origin <브랜치>`를 실행한다 (거부되면 사용자에게 push를 요청).
3. `git log <대상>..HEAD --oneline`과 `git diff <대상>...HEAD --stat`으로 변경을 요약한다.
4. `.gitlab/merge_request_templates/Default.md` 템플릿의 **모든 절**을 채운다:
   - 영향 범위 체크박스는 실제 변경된 앱만
   - 검증 결과는 실제로 실행해 확인한 것만 체크 (안 한 것은 빈 채로 두고 명시)
   - 리뷰 참고 사항에 리뷰어가 집중할 부분과 남은 결정을 적는다
5. 타이틀(대표 커밋 제목과 동일)과 본문을 각각 코드블록으로 출력한다 — 사용자가 복사해 GitLab 웹에서 MR을 만든다. **MR을 임의로 등록하지 않는다.**
