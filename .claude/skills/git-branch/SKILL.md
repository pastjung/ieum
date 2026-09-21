---
name: git-branch
description: 새 작업 브랜치를 규칙에 맞게 만든다. 사용자가 브랜치 생성을 요청하거나 새 이슈 작업을 시작할 때 사용.
---

# 브랜치 생성 절차

1. docs/conventions/git/branch-strategy.md의 이름 형식과 시작 브랜치 규칙을 확인한다.
2. 시작 브랜치를 정한다: 일반 작업은 `dev`에서(`git checkout dev && git pull` 후), 상위 통합 feat의 하위 작업은 그 상위 브랜치에서.
3. 이름을 짓는다: `<type>/[<이슈번호>-]<도메인>[-<기능>]` (소문자·숫자·하이픈). 이슈가 있으면 번호를 반드시 포함한다.
4. 사용자에게 브랜치 이름을 확인받고 `git checkout -b`로 생성한다.
5. `bash scripts/check-branch-name.sh`를 실행해 규칙 통과를 확인한다. 실패하면 `git branch -m`으로 고친다.
