---
name: code-review
description: 변경사항을 팀 기준으로 코드리뷰한다. 구현을 마쳤을 때, MR을 올리기 전, 사용자가 리뷰를 요청할 때 사용.
---

# 코드리뷰 절차

1. 리뷰 범위를 정한다: MR 대상 브랜치 기준 diff (`git diff <대상>...HEAD`). 대상 판단은 docs/conventions/git/branch-strategy.md.
2. **code-reviewer 서브에이전트에 위임한다.** 리뷰 기준은 docs/conventions/quality/review-checklist.md.
3. 결과를 심각도(P0~P3) 순으로 정리해 보고한다.
4. P0·P1 지적은 수정 여부를 사용자에게 확인하고, 수정했으면 해당 부분만 재리뷰한다.
5. 발견이 없으면 "No findings."를 그대로 전달한다 — 억지 지적을 만들지 않는다.
