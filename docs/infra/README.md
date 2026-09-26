# 인프라 개요

이음(잇다) 프로젝트의 인프라 전체를 조망하는 문서. **처음 온 사람은 이 문서만 읽으면 "무엇이 있고, 나는 뭘 하면 되는지"를 알 수 있는 것**이 목표다. 세부 내용은 각 문서로 링크한다.

## 1. 전체 그림

```
[사용자] ── HTTPS ──> EC2 호스트 Nginx ──┬── /      → web (React+nginx)     blue/green
                                         ├── /api/  → backend (Spring Boot) blue/green
                                         └── (내부)  → ai (FastAPI)          blue/green
                                                        ↑ backend만 호출 가능 (외부 차단)

[개발자] ── MR ──> GitLab ── Webhook ──> Jenkins ──┬── MR 검증 + AI 코드리뷰
                                                   ├── release/태그 → 빌드 → 무중단 배포
                                                   ├── 수동 롤백
                                                   └── Mattermost 알림 (AI 변경 요약)
```

- 서버 1대(EC2)에 전 서비스 + Jenkins가 컨테이너로 산다. 무중단 배포는 서비스마다 blue/green 슬롯 2개를 두고 Nginx가 트래픽을 전환하는 방식.
- **dev/main 병합은 배포를 일으키지 않는다.** 서버로 가는 유일한 통로는 release 브랜치와 v* 태그다.

## 2. 문서 지도

| 문서 | 다루는 것 |
| --- | --- |
| [architecture.md](./architecture.md) | 아키텍처 다이어그램 2장 — 사용자 요청 흐름, CI/CD 전체 흐름 |
| [docker.md](./docker.md) | 단일 멀티스테이지 Dockerfile 전략, 로컬 개발(compose --watch), 이미지 보존 정책 |
| [deploy.md](./deploy.md) | EC2 구축, HTTPS(Certbot), 3중 방어, 수동 배포·롤백 절차(비상용), blue-green 원리 |
| [jenkins.md](./jenkins.md) | Jenkins 설치 구성, docker.sock 보안 원칙, Credentials 계약 표, 백업 |
| [openvidu.md](./openvidu.md) | 실시간 수업 음성(OpenVidu 3.8) — 구성, 포트·ufw, 공인 IP 고정, backend 연결 설정, 트러블슈팅 |
| [pipeline.md](./pipeline.md) | MR 검증·AI 리뷰·배포·롤백 파이프라인, Mattermost 알림, **재현 체크리스트(7절)**, 트러블슈팅 사례집 |

팀 노션에도 같은 주제의 개념 설명 문서가 있다 (초심자용 — 주요 로직 설계 페이지).

## 3. 실전 가이드: 나는 뭘 하면 되나

### 3.1 모든 팀원 — 기능 개발 흐름

1. 작업 브랜치에서 개발 → **MR 생성 (대상: dev)**
2. verify 파이프라인이 자동 실행된다 — **초록불(✅)을 기다렸다가 병합한다** (팀 규칙: 파이프라인 성공이 병합 조건). ❌면 상태 아이콘 클릭 → Jenkins 로그에서 실패 원인 확인
3. AI 코드리뷰 코멘트가 달린다 — **참고용이며 병합을 막지 않는다.** 명백한 지적만 반영하고, 이상한 지적은 무시해도 된다
4. 승인·병합해도 재검증은 돌지 않는다 (커밋이 바뀔 때만 검증)

알아둘 것: 첫 빌드는 5~10분(캐시 없음), 이후 1~2분. 문서만 고친 MR은 수십 초.

### 3.2 릴리스 담당자 — 릴리스 사이클

```
① release/X.Y.Z 생성 (GitLab 웹 → Branches → New branch, dev 기준)
     └ ⚠️ 웹에서만 가능 (보호 브랜치라 CLI push 거부됨). 생성 즉시 첫 rc 자동 배포
② 릴리스 기간: 수정은 MR(대상: release/X.Y.Z) → 병합 = 변경 서비스만 자동 재배포
③ 릴리스 확정: release → main MR 병합 → main에 vX.Y.Z 태그 생성 (웹 → Tags → New tag)
     └ 전체 서비스가 태그명으로 배포됨 (공식 롤백 지점)
④ 역병합: release → dev MR (릴리스 기간의 수정을 dev에도 반영)
     └ 이 MR은 "Checking pipeline status"가 안 멈출 수 있음 — Jenkins에서 verify 성공
       확인 후 Merge immediately (pipeline.md 트러블슈팅 참고)
⑤ release 브랜치 삭제 (웹) — 태그와 이미지는 남는다
```

release에 수정이 하나도 없었으면 ④는 생략한다. 배포·롤백 결과는 Mattermost 채널로 자동 통지된다 (배포 성공 알림에는 AI가 쓴 변경 요약 포함).

### 3.3 문제가 생겼을 때 — 롤백

Jenkins → `itda-rollback` → **Build with Parameters** → SERVICE 선택, TAG 입력 → 1~2분 내 복구. 어느 태그로 돌아갈지 모르면 TAG를 비우고 실행하면 보관된 태그 목록이 나온다. 판단 기준과 즉시 롤백(수 초, 서버 접속 필요)은 [deploy.md 7.3절](./deploy.md) 참고. 원칙: **복구가 원인 분석보다 먼저다.**

### 3.4 비상시 — Jenkins가 죽었을 때

서비스는 Jenkins와 독립적이라 계속 돈다. 배포·롤백이 급하면 EC2에서 사람이 직접:

```bash
docker build -t itda-backend:<태그> apps/backend   # repo 클론 후
/home/ubuntu/app/scripts/deploy.sh backend <태그>   # 파이프라인과 100% 같은 스크립트
```

### 3.5 운영 설정(.env.config) 변경 — DB 접속 정보, 프로필 등

운영 설정의 원본은 서버가 아니라 **Jenkins의 Secret file credential `itda-env-config`**다. 변경 절차:

1. Jenkins → Manage Jenkins → Credentials → `itda-env-config` → Update → 새 파일 업로드 (기존 내용에서 필요한 값만 바꾼 파일)
2. 재배포로 반영 — 환경변수는 컨테이너 시작 시 읽히므로, `itda-rollback` 잡에 **현재 태그 그대로** 넣어 실행하면 코드는 그대로인 채 새 설정으로 무중단 재기동된다
3. 항목(키)이 추가·삭제됐으면 repo `deploy/.env.config.example`도 MR로 갱신 (값 말고 구조의 원본은 repo)

서버의 `.env.config`를 직접 고치면 **다음 배포 때 Jenkins 원본으로 덮여 사라진다.** `.env.tags`(배포 태그 상태)는 deploy.sh 전용이니 사람이 절대 건드리지 않는다.

## 4. 하지 말 것 (사고 방지 규칙)

- **서버의 Nginx 설정·compose를 직접 수정하지 않는다** — 원본은 repo이고 배포 때마다 자동 동기화되어 서버 수정은 조용히 사라진다. 수정은 repo → MR로
- **`docker image prune -a` 금지** — 롤백 지점이 전멸한다 (정리는 파이프라인이 자동으로)
- **환경 파일·인증키를 Git에 올리지 않는다** — .gitignore가 막고 있지만 우회하지 말 것. 운영 설정 변경은 서버 편집이 아니라 3.5절 절차로
- **release/main/dev에 직접 push하지 않는다** — 보호 설정이 막고 있고, 모든 변경은 MR로

## 5. 지금까지의 작업 이력 (요약)

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1. Docker | 앱별 단일 멀티스테이지 Dockerfile, 로컬 통합 개발 환경 | ✅ |
| 2. 배포 환경 | EC2 + HTTPS + 3중 방어 + blue-green 무중단 배포·롤백 | ✅ |
| 3. Jenkins | 컨테이너 설치, Nginx 경유, Credentials 체계 | ✅ |
| 4. CI/CD | MR 검증, AI 코드리뷰, 자동 배포(rc/v*), 롤백 잡, 인프라 동기화, 이미지 정리, Mattermost 알림 | ✅ (릴리스 사이클 3회 실전 검증) |
| 5. 모니터링 | Prometheus + Grafana, 운영 대시보드 | 예정 |
| - | RDS 연결 (현재 backend는 H2 임시 운용 — 재배포 시 데이터 소실) | 팀 일정 대기 |

운영 주의: GitLab 토큰(만료 4주)은 만료 전 재발급 필요 — 절차는 [pipeline.md 7.1절](./pipeline.md).
