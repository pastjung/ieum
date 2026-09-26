#!/bin/bash
# 커밋 메시지 규칙 검사 — 원본 규칙: docs/conventions/git/commit-convention.md
# 형식: <type>(<scope>): <summary>   (scope 생략 가능)

msg_file="$1"
first_line=$(head -n1 "$msg_file")

# git이 만드는 커밋(머지, 리버트, fixup, squash)은 통과
case "$first_line" in
  Merge*|Revert*|fixup!*|squash!*|amend!*) exit 0 ;;
esac

pattern='^(feat|fix|refactor|test|docs|style|perf|build|ci|chore)(\([a-zA-Z0-9,/ -]+\))?: .+'

if [[ "$first_line" =~ $pattern ]]; then
  exit 0
fi

cat >&2 <<MSG
[commit-msg] 커밋 메시지가 팀 규칙에 맞지 않습니다:

  입력: $first_line
  형식: <type>(<scope>): <summary>   예: feat(user): 이메일 로그인 API 구현
  type: feat fix refactor test docs style perf build ci chore

  방금 메시지 수정: git commit --amend
  규칙 문서: docs/conventions/git/commit-convention.md
MSG
exit 1
