#!/bin/bash
# 브랜치 이름 규칙 검사 — 원본 규칙: docs/conventions/git/branch-strategy.md
# 형식: <type>/[<issue-number>-]<domain>[-<feature>]  (영문 소문자·숫자·하이픈)

branch=$(git rev-parse --abbrev-ref HEAD)

# 장기·릴리스·핫픽스 브랜치는 그대로 허용
case "$branch" in
  main|dev|HEAD) exit 0 ;;
  release/*|hotfix/*) exit 0 ;;
esac

pattern='^(feat|fix|refactor|test|docs|style|perf|build|ci|chore)/([0-9]+-)?[a-z0-9]+(-[a-z0-9]+)*$'

if [[ "$branch" =~ $pattern ]]; then
  exit 0
fi

cat >&2 <<MSG
[branch-name] 브랜치 이름이 팀 규칙에 맞지 않습니다: $branch

  형식: <type>/[<이슈번호>-]<도메인>[-<기능>]   (소문자·숫자·하이픈)
  type: feat fix refactor test docs style perf build ci chore
  예:   feat/456-user-login, fix/lesson-empty-list, chore/harness-pre-commit

  브랜치 이름 변경: git branch -m <새이름>
  규칙 문서: docs/conventions/git/branch-strategy.md
MSG
exit 1
