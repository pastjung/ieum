---
description: 변경된 앱의 검증(린트·타입체크·테스트)을 실행하고 실패를 전부 수정한다
allowed-tools: Bash, Read, Edit, Grep, Glob
---

1. `git status`와 `git diff dev...HEAD --name-only`로 변경된 앱(apps/backend·ai·web·mobile)을 파악하라. 변경이 없으면 "변경 없음"만 보고하라.
2. AGENTS.md의 검증 명령 중 해당 앱의 것을 실행하라.
3. 실패가 있으면 원인을 고치고 재실행하라. 전부 통과할 때까지 반복하되, **같은 실패가 3회 반복되면 멈추고 상황을 보고하라** (탈출 조건).
4. 앱별 결과를 한 줄씩 요약하라 (통과/수정한 것/남은 것).
