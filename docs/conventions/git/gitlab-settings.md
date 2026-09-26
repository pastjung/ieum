# GitLab 프로젝트 설정

## 1. 목적

이 문서는 이음(I:EUM) 프로젝트에서 사용하는 GitLab 저장소와 Merge Request 설정을 정의한다.

프로젝트 구성원은 동일한 브랜치 보호 규칙과 병합 조건을 사용한다. GitLab 화면의 설정과 이 문서가 다르면 Maintainer가 설정을 확인하고 문서를 함께 갱신한다.

## 2. 기본 브랜치

| 항목 | 설정값 |
| --- | --- |
| Default branch | `main` |
| Auto-close referenced issues on default branch | 사용 |
| Branch name template | `%{id}-%{title}` |

- 모든 MR은 대상 브랜치를 직접 확인한 뒤 생성한다.
- 기능 및 유지보수 브랜치의 기본 병합 대상은 `dev`이다.
- GitLab Issue에서 생성한 브랜치는 작업 유형에 맞게 `feat/`, `fix/`, `chore/` 등의 접두사를 붙인다.
- 이슈 없이 생성하는 브랜치는 Git 브랜치 전략의 이름 규칙을 직접 적용한다.

## 3. 브랜치 보호 규칙

`Settings > Repository > Branch rules`에서 다음 규칙을 사용한다.

| 대상 | Allowed to merge | Allowed to push and merge | Force push |
| --- | --- | --- | :---: |
| `main` | Maintainers | No one | 사용하지 않음 |
| `dev` | Developers + Maintainers | No one | 사용하지 않음 |
| `release/*` | Maintainers | No one | 사용하지 않음 |

- 보호 브랜치에는 직접 push하지 않고 MR로만 반영한다.
- `release/*` 규칙은 일치하는 릴리스 브랜치가 없어도 미리 등록해 둔다.
- 릴리스 브랜치는 GitLab의 `Code > Branches > New branch`에서 `dev`를 기준으로 생성한다.
- 서로 겹치는 wildcard 규칙은 더 느슨한 권한이 적용될 수 있으므로 추가하지 않는다.

## 4. Merge Request 기본 설정

`Settings > Merge requests`에서 다음 값을 사용한다.

| 항목 | 설정값 |
| --- | --- |
| Merge method | Merge commit |
| Squash commits when merging | Allow |
| All threads must be resolved | 사용 |
| Enable Delete source branch option by default | 사용 |
| Automatically resolve outdated diff threads | 사용하지 않음 |
| Pipelines must succeed | Jenkins 연동 전까지 사용하지 않음 |
| Skipped pipelines are considered successful | 사용하지 않음 |

Merge commit을 사용하여 기능, 개발, 릴리스 브랜치의 병합 지점을 기록한다. Squash는 프로젝트 전체에서 강제하지 않고 MR 유형에 따라 선택한다.

코드가 변경되어 리뷰 스레드가 오래된 상태가 되어도 자동으로 해결하지 않는다. 작성자가 수정 내용을 알리고 리뷰어가 확인한 후 스레드를 직접 해결한다.

## 5. MR 대상과 Squash

| 병합 흐름 | 대상 브랜치 | Squash | 소스 브랜치 삭제 |
| --- | --- | :---: | :---: |
| 하위 작업 브랜치 | 상위 기능 브랜치 | 사용 | 사용 |
| 단일 작업 브랜치 | `dev` | 사용 | 사용 |
| 상위 기능 브랜치 | `dev` | 사용하지 않음 | 사용 |
| `dev` | `release/*` | 사용하지 않음 | 사용하지 않음 |
| `release/*` | `main` | 사용하지 않음 | 릴리스 완료 후 삭제 |
| `hotfix/*` | `main` | 사용 | 사용 |

- `main`과 `dev`는 장기 브랜치이므로 삭제하지 않는다.
- 릴리스 브랜치는 `main`과 `dev` 반영, 배포 검증, 태그 생성이 모두 끝난 후 삭제한다.
- GitLab이 기본 MR 대상을 `main`으로 제안하더라도 작업 브랜치는 `dev` 또는 상위 기능 브랜치를 선택한다.

## 6. 리뷰 운영

현재 GitLab 환경에서는 Merge Request Approvals 기능을 제공하지 않으므로 승인 인원을 시스템으로 강제하지 않는다.

리뷰는 다음 방식으로 진행한다.

- MR 작성자는 Reviewer를 지정한다.
- 리뷰어는 변경 파일, 테스트 결과, 영향 범위를 확인한다.
- 수정이 필요한 내용은 해결 가능한 스레드로 남긴다.
- 작성자는 수정 후 스레드에 결과를 알린다.
- 모든 스레드가 해결되고 병합 전 확인 목록을 충족한 후 병합한다.
- `main`과 `release/*` 병합은 Maintainer가 수행한다.

## 7. Jenkins 연동 (연동 완료)

MR 검증 파이프라인이 운영 중이다: MR 생성과 새 커밋 push가 Jenkins 검증(`verify`)을 실행하고, 결과가 MR에 ✅/❌로 표시된다. 변경된 애플리케이션이 없는 MR(문서만 수정 등)도 테스트를 스킵한 채 `success`를 회신한다. 구성 상세는 [docs/infra/pipeline.md](../infra/pipeline.md).

`Pipelines must succeed`는 연동 후에도 **사용하지 않는다** — 이유가 바뀌었다: 소스가 보호 브랜치인 MR(릴리스 역병합 등)은 권한 제약으로 검증 상태가 GitLab에 기록되지 않아, 이 옵션이 켜져 있으면 해당 MR의 병합이 영구 차단된다. 대신 "MR 병합 조건: 파이프라인 성공"을 팀 규칙으로 지킨다 — **verify ✅를 확인한 뒤 병합한다.**

## 8. 관련 문서

- [저장소 폴더 구조](./repository-structure.md)
- [Git 브랜치 전략](./branch-strategy.md)
- [인프라 개요 (CI/CD·배포·롤백)](../infra/README.md)
- [Git 커밋 전략](./commit-convention.md)

