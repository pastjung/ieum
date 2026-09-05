# CI/CD 파이프라인

## 1. 목적

이 문서는 Jenkins 파이프라인의 구성과 동작, 설정 방법을 정의한다. 현재 구현된 것은 MR 검증 파이프라인이며, AI 코드리뷰·배포·롤백 파이프라인은 구현되는 대로 이 문서에 추가한다.

전체 트리거 설계 (계획 포함):

| 파이프라인 | 트리거 | 동작 | 상태 |
| --- | --- | --- | --- |
| MR 검증 (`itda-mr-verify`) | MR 생성·갱신 (대상: dev/main/release) | 변경 서비스만 테스트, 결과를 MR에 회신 | ✅ 운영 중 |
| AI 코드리뷰 | MR 검증 파이프라인의 스테이지 | GMS로 diff 리뷰, MR 코멘트 작성 (비차단) | 구현 중 |
| 배포 | `release/*` push, `v*` tag | 변경 서비스 빌드 → blue-green 배포 | 예정 |
| 롤백 | 수동 (서비스, 태그 파라미터) | 보관된 이미지로 재배포 | 예정 |

## 2. MR 검증 파이프라인 (`Jenkinsfile.mr`)

### 2.1 동작 흐름

```text
MR 생성/커밋 추가 (GitLab)
  → Webhook (Merge request events)
  → Jenkins 잡 itda-mr-verify
      1. Changes  : MR 대상 브랜치와의 git diff로 변경 서비스 판별
      2. Verify   : 변경된 서비스만 병렬로 docker build --target test
      3. (예정) AI Review
  → 결과를 GitLab 커밋 상태 'verify'로 회신 → MR 화면에 ✅/❌ 표시
```

- 문서만 바뀐 MR은 모든 테스트가 스킵되어 수십 초에 끝난다.
- 테스트 실패 = 해당 서비스의 `docker build --target test` 실패 = verify ❌. MR의 상태 아이콘을 클릭하면 해당 Jenkins 빌드(Console Output)로 이동한다.
- 첫 빌드는 캐시(의존성 레이어, BuildKit 캐시)가 비어 있어 5~10분 걸리지만 이후는 크게 짧아진다. 의존성 파일(build.gradle, requirements)이 바뀐 빌드도 다시 길어진다 (정상).

### 2.2 test 스테이지 (Dockerfile)

MR 검증은 각 앱 Dockerfile의 `test` 스테이지를 빌드하는 것으로 수행한다 (`docker build --target test`). `RUN` 명령이 실패하면 빌드가 실패하는 성질을 이용해 "빌드 성공 = 테스트 통과"로 만든 구조다.

| 앱 | test 스테이지 내용 | 비고 |
| --- | --- | --- |
| backend | `gradlew test` (BuildKit 캐시로 Gradle 캐시 재사용) | |
| ai | dev 의존성 설치 + `pytest` | |
| web | 별도 스테이지 없음 — 운영 풀빌드(`docker build`)가 tsc 타입체크를 포함하므로 그 자체가 검증 | 테스트 도입 시 추가 |

이 방식을 택한 이유: 테스트 환경 = 빌드 환경이 Dockerfile 하나로 통일되고, Jenkins에 언어 런타임을 설치할 필요가 없으며, DooD(docker.sock) 구조에서 볼륨 마운트 경로가 꼬이는 함정을 피한다. test 스테이지는 운영 이미지 경로에 포함되지 않는다.

### 2.3 변경 감지 방식

Jenkins의 `changeset` 조건은 브랜치 첫 빌드에서 빈 결과를 내는 함정이 있어 쓰지 않는다. 대신 MR 대상 브랜치와의 diff로 직접 판별한다:

```groovy
git diff --name-only origin/<대상브랜치>...HEAD | grep -q '^apps/backend/'
```

주의: checkout 단계가 `+refs/heads/*` refspec으로 모든 브랜치를 이미 가져오므로 `origin/<대상>`은 추가 fetch 없이 존재한다. **파이프라인 sh 단계에는 git 자격증명이 없어서 `git fetch`를 실행하면 인증 실패한다** — fetch가 필요해 보이면 이 사실부터 의심할 것.

### 2.4 Jenkins 잡 설정 (`itda-mr-verify`)

| 항목 | 값 | 이유 |
| --- | --- | --- |
| 타입 | Pipeline (script from SCM) | 파이프라인 정의를 repo에서 관리 |
| Script Path | `Jenkinsfile.mr` | |
| Branch Specifier | `origin/${gitlabSourceBranch}` | MR의 소스 브랜치를 체크아웃 |
| Lightweight checkout | 해제 | 변수 브랜치와 궁합 문제 방지 |
| Trigger | Opened MR Events + Rebuild on push to source branch | MR 생성·커밋 추가 시 실행 |
| Ignore WIP Merge Requests | **해제** | Draft MR도 검증 대상 (기본값은 켜짐 — 아래 트러블슈팅) |
| Target Branch Regex | `^(dev|main|release\/.+)$` | dev·main·release 대상 MR만 검증. 하위 작업 → 상위 feat MR은 제외 (자원 절약, dev 진입 시 재검증됨) |

GitLab 쪽: Webhook(Merge request events만, Secret token 포함) + 커밋 상태 회신용 GitLab 연결(`ssafy-gitlab`, Manage Jenkins → System).

### 2.5 Credentials (jenkins.md 6절의 계약 표 참조)

- `gitlab-repository-read` — checkout용 (읽기 전용)
- `gitlab-api-token` — 커밋 상태 회신 + MR 코멘트용 (api scope)
- `gms-api-key` — AI 리뷰 LLM 호출용

## 3. 트러블슈팅 사례집 (실제 겪은 것)

**웹훅은 200인데 빌드가 안 생김** → GitLab 플러그인의 "Ignore WIP Merge Requests"(기본 켜짐)가 Draft MR을 무시한 것. 웹훅 Recent events의 payload에서 `"work_in_progress": true` 확인 → 잡 설정에서 해당 옵션 해제. (진단 요령: Recent events의 응답 코드·본문이 첫 단서)

**GitLab 연결 Test Connection이 403** → 프로젝트 액세스 토큰은 `/user` 엔드포인트에 답하지 못한다 (프로젝트 범위 밖). 실제 기능(커밋 상태, 코멘트)은 프로젝트 범위 API라 정상 동작하므로, `curl -H "PRIVATE-TOKEN: ..." .../api/v4/projects/<경로>`가 200이면 테스트 실패는 무시하고 저장한다.

**API 호출이 403 (insufficient_scope)** → 401이 아닌 403은 "실존 토큰인데 권한 부족" — 다른 토큰(read 전용)의 값을 잘못 쓴 경우가 대표적. 토큰 목록의 Last Used로 어느 토큰이 실제 인증됐는지 역추적할 수 있다.

**파이프라인에서 `git fetch`가 "could not read Username"으로 실패** → sh 단계에는 git 자격증명이 주입되지 않는다 (2.3절). checkout이 이미 모든 브랜치를 가져오므로 fetch 자체가 불필요.

**Jenkins 설정 폼에서 "No valid crumb" 403** → CSRF 토큰 만료. 새로고침 후 재시도, 반복되면 Security 설정의 "Enable proxy compatibility".

## 4. AI 코드리뷰 / 5. 배포 파이프라인 / 6. 롤백

(구현되는 대로 작성)
