#!/usr/bin/env bash
# =========================================================================
# 이미지 보존 정책 집행 (docker.md 5절) — EC2 호스트에서 실행
#
# 서비스별로 릴리스 이미지(v*)와 검증 이미지(*-rc.*)를 각각 최근
# KEEP(기본 3)개만 남기고 삭제한다. 배포 파이프라인 끝에서 자동 실행되며,
# 수동 실행도 안전하다.
#
# 안전장치: 컨테이너(활성/대기 슬롯)가 사용 중인 이미지는 docker rmi가
# 거부하므로 절대 삭제되지 않는다 — 실패는 "건너뜀"으로 처리.
# docker image prune -a는 롤백 지점을 전멸시키므로 금지 (docker.md).
# =========================================================================
set -euo pipefail

KEEP="${KEEP:-3}"

for svc in web backend ai; do
  repo="itda-$svc"
  tags="$(docker images "$repo" --format '{{.Tag}}')"   # 최신순 정렬로 출력됨

  for pattern in '^v[0-9]' '\-rc\.'; do
    printf '%s\n' "$tags" \
      | grep -E "$pattern" \
      | tail -n +$((KEEP + 1)) \
      | while read -r tag; do
          [ -n "$tag" ] || continue
          if docker rmi "$repo:$tag" >/dev/null 2>&1; then
            echo "[cleanup] 삭제: $repo:$tag"
          else
            echo "[cleanup] 건너뜀(사용 중): $repo:$tag"
          fi
        done || true
  done
done

echo "[cleanup] 완료 — 서비스별 v* / rc 각 최근 ${KEEP}개 유지"
