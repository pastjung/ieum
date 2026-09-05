# CI/CD 파이프라인

## 1. 목적

이 문서는 Jenkins 파이프라인의 구성과 동작, 설정 방법을 정의한다. 현재 구현된 것은 MR 검증 파이프라인이며, AI 코드리뷰·배포·롤백 파이프라인은 구현되는 대로 이 문서에 추가한다.

전체 트리거 설계 (계획 포함):

| 파이프라인 | 트리거 | 동작 | 상태 |
| --- | --- | --- | --- |
| MR 검증 (`itda-mr-verify`) | MR 생성·갱신 (대상: dev/main/release) | 변경 서비스만 테스트, 결과를 MR에 회신 | ✅ 운영 중 |
| AI 코드리뷰 | MR 검증 파이프라인의 스테이지 | GMS로 diff 리뷰, MR 코멘트 작성 (비차단) | ✅ 운영 중 |
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

**GMS 호출이 400 Bad Request** → GPT-5 계열 모델은 `max_tokens`를 거부하고 `max_completion_tokens`를 요구하며, `temperature`도 기본값 외에는 거부한다. 스크립트가 에러 응답 본문을 로그에 출력하므로 정확한 원인은 로그에서 확인.

**withCredentials에서 "is of type 'GitLab API token' where ... expected"** → GitLab 플러그인 전용 credential 타입은 string 바인딩과 호환되지 않는다. 같은 값을 Secret text 타입(`gitlab-api-token-text`)으로 별도 등록해 참조한다.

**Jenkins 설정 폼에서 "No valid crumb" 403** → CSRF 토큰 만료. 새로고침 후 재시도, 반복되면 Security 설정의 "Enable proxy compatibility".

## 4. AI 코드리뷰 (`scripts/ai-review.py`)

MR 검증 파이프라인의 마지막 스테이지. 테스트가 게이트라면 AI 리뷰는 권고다 — `catchError`로 감싸 리뷰가 실패해도 빌드는 성공을 유지한다(비차단). 따라서 **빌드가 passed여도 AI 리뷰는 실패했을 수 있다** — 코멘트가 안 달렸으면 빌드의 Stages에서 AI Review 상태를 확인한다.

동작:

1. GitLab API로 MR diff 수집 — lock·생성 파일 제외, 파일당 4천자·전체 1.6만자 상한
2. 변경된 서비스의 README(컨벤션 문서)를 MR 소스 브랜치 기준으로 읽어 시스템 프롬프트에 주입 (문서를 못 읽으면 기본 규칙으로 폴백)
3. GMS 호출 (`gpt-5.4-mini`, 모델·엔드포인트는 Jenkinsfile 환경변수로 조정)
4. 결과를 MR 코멘트로 upsert — 숨은 마커(`<!-- itda-ai-review -->`)로 기존 코멘트를 찾아 갱신하므로 MR당 리뷰 코멘트는 항상 1개

실행 방식: `python:3.12-slim` 컨테이너에 스크립트를 stdin으로 주입(`docker run -i ... python - < scripts/ai-review.py`). 볼륨 마운트가 없어 DooD 경로 문제가 없고, 표준 라이브러리만 사용해 pip 설치가 없다. 시크릿은 `withCredentials`가 셸 환경변수로 주입하고 `docker run -e 이름` 형태(값 없이 이름만)로 상속시켜 콘솔 로그에 값이 노출되지 않는다.

## 5. 배포 파이프라인 (`Jenkinsfile`)

### 5.1 동작 흐름

```text
release/* push  또는  v* 태그 push (GitLab)
  → Webhook (Push events: release/* 필터 / Tag push events)
  → Jenkins 잡 itda-deploy
      1. Plan   : 무엇을 어떤 태그로 배포할지 결정 (아래 표)
      2. Build  : 대상 서비스 이미지 빌드 (호스트 Docker 저장소에 저장 = 롤백 지점)
      3. Deploy : 자기 호스트에 SSH → 서비스마다 deploy.sh 실행 (blue-green)
  → 결과를 GitLab 커밋 상태 'deploy'로 회신
```

| 트리거 | 대상 서비스 | 이미지 태그 | 의미 |
| --- | --- | --- | --- |
| `release/1.2.0` push | 마지막 성공 배포 이후 **변경된 서비스만** | `1.2.0-rc.<빌드번호>-<sha>` | 검증 배포 (release 기간 반복) |
| `v1.2.0` 태그 push | **세 서비스 전부** | `v1.2.0` (태그명 그대로) | 릴리스 확정 |
| `app-v*` 태그, 브랜치 삭제, 그 외 ref | 없음 — Plan에서 스킵 | - | 서버 배포 대상 아님 |

핵심 원칙 재확인: **dev/main 병합은 배포를 일으키지 않는다.** 서버로 나가는 유일한 경로는 release 브랜치와 v* 태그이고, **release 브랜치는 반드시 dev에서 생성한다.**

### 5.2 Plan — 변경 감지와 태그 결정

release push에서는 `GIT_PREVIOUS_SUCCESSFUL_COMMIT`(이 잡의 마지막 성공 빌드 커밋)과의 diff로 변경 서비스를 판별한다. MR 검증(2.3절)이 "대상 브랜치와의 diff"인 것과 달리 여기는 "마지막으로 서버에 나간 코드와의 diff"가 올바른 기준이기 때문. 비교 기준이 없으면(잡의 첫 빌드 등) 안전하게 전체를 배포한다.

rc 태그에 빌드번호+sha를 넣는 이유: release 기간 중 같은 브랜치에 여러 번 push되므로 각 배포를 유일하게 식별해야 롤백 대상을 지정할 수 있다.

### 5.3 Deploy — 왜 SSH인가

이미지 빌드는 docker.sock으로 호스트 Docker에서 수행되지만, `deploy.sh`는 `sudo nginx reload` 등 **호스트에서만 실행 가능한 명령**을 포함하므로 컨테이너 안에서 돌릴 수 없다. 그래서 Jenkins가 자기 호스트에 SSH로 접속해 실행한다:

```text
Jenkins 컨테이너 → ssh ubuntu@host.docker.internal → /home/ubuntu/app/scripts/deploy.sh <svc> <tag>
```

- `host.docker.internal`은 Jenkins compose의 `extra_hosts: host-gateway`가 부여한 "자기 호스트" 주소
- 사람이 수동 배포할 때와 **100% 같은 스크립트, 같은 경로** — 자동/수동 배포가 다르게 동작하는 사고가 구조적으로 불가능
- 배포 중 배포 금지: `disableConcurrentBuilds`로 동시 실행을 차단 (.env·inc 파일 동시 수정 방지)

SSH 키 준비 (1회):

```bash
# EC2에서 — 키쌍 생성 + 공개키를 authorized_keys에 등록
ssh-keygen -t ed25519 -f /tmp/jenkins-deploy-key -N "" -C "jenkins-deploy"
cat /tmp/jenkins-deploy-key.pub >> ~/.ssh/authorized_keys
cat /tmp/jenkins-deploy-key          # 출력 전체를 Jenkins Credentials에 등록

# Jenkins: Credentials → SSH Username with private key, ID ec2-deploy-key, Username ubuntu

# 등록 확인 후 임시 키 파일 삭제 (키는 이제 Jenkins 금고에만 존재)
rm /tmp/jenkins-deploy-key /tmp/jenkins-deploy-key.pub
```

### 5.4 Jenkins 잡 설정 (`itda-deploy`)

| 항목 | 값 | 이유 |
| --- | --- | --- |
| 타입 | Pipeline (script from SCM) | |
| Script Path | `Jenkinsfile` (root) | `.mr`과 별개 파일 |
| Branch Specifier | `${gitlabBranch}` | push된 브랜치/태그를 그대로 체크아웃 (태그 push 시 `refs/tags/v*`가 들어옴) |
| Refspec | **비움** | 기본 동작이 모든 브랜치+태그 fetch — 명시하면 이 버전 검증기가 거부 |
| Lightweight checkout | 해제 | |
| Trigger | Push Events만 (MR 계열 전부 해제) + Secret token | 태그 push도 Push Events로 수신됨 |

GitLab Webhook(`jenkins-deploy`): Push events(Wildcard `release/*`) + Tag push events, Secret token. GitLab이 1차로 거르고 Plan 스테이지가 2차로 거르는 이중 필터 — 웹훅 필터가 실수로 풀려도 Plan의 ref 검사가 막는다.

### 5.5 배포 확인과 실패 시

- 빌드 콘솔에 `배포 계획: [서비스...] 태그 ...`부터 서비스별 헬스체크·Nginx 전환 로그가 순서대로 남는다. 빌드 설명(description)에도 태그와 대상이 표시된다.
- 헬스체크 실패 시 deploy.sh가 해당 서비스 배포를 자체 취소하므로(deploy.md 7절) 실패한 빌드가 있어도 서비스는 직전 상태 그대로다.
- Jenkins 장애 시에도 EC2에서 같은 스크립트를 직접 실행하면 배포·롤백 가능: `./scripts/deploy.sh backend <태그>`

## 6. 롤백 파이프라인

(구현되는 대로 작성 — 현재는 deploy.md 7.3절의 수동 롤백 절차 사용)
