---
description: 변경된 앱의 검증(린트·타입체크·테스트)을 실행하고 실패를 전부 수정한다
allowed-tools: Bash, Read, Edit, Grep, Glob
---

1. 비교 기준 브랜치를 정하라 (docs/conventions/git/branch-strategy.md의 병합 대상과 같다):
   - 현재 브랜치가 `release/*` 또는 `hotfix/*` → `origin/main`
   - 그 외 작업 브랜치 → `origin/dev`
   - 단, 상위 통합 `feat/*` 브랜치에서 갈라져 나온 하위 작업이면 그 상위 브랜치
   - 판단이 애매하면 사용자에게 기준 브랜치를 물어라
2. `git status`와 `git diff <기준>...HEAD --name-only`로 변경된 앱(apps/backend·ai·mobile, front/apps/web)을 파악하라. 변경이 없으면 "변경 없음"만 보고하라.
3. AGENTS.md의 검증 명령 중 해당 앱의 것을 실행하라 (web은 `cd front && pnpm --filter @ieum/web build`).
4. 실패가 있으면 원인을 고치고 재실행하라. 전부 통과할 때까지 반복하되, **같은 실패가 3회 반복되면 멈추고 상황을 보고하라** (탈출 조건).
5. 사용한 기준 브랜치와 앱별 결과를 한 줄씩 요약하라 (통과/수정한 것/남은 것).
