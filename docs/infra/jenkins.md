# Jenkins 구축

## 1. 목적

이 문서는 CI/CD 서버인 Jenkins의 설치 구성과 그 이유, 초기 설정, Credentials 관리 기준을 정의한다. 파이프라인(잡, Webhook, Jenkinsfile)은 4단계에서 별도 문서로 다룬다.

- 접속: `https://j15b201.p.ssafy.io/jenkins/` (호스트 Nginx 경유, 관리자 계정은 팀 비공개 페이지에 기록)
- 설치 정의 원본: repo `deploy/jenkins/` (Dockerfile, docker-compose.yml, .env.example)

## 2. 사전 지식

- **Jenkins**: "GitLab에 코드가 올라오면 → 테스트하고 → 이미지를 빌드하고 → 배포해라" 같은 절차를 대신 실행해주는 자동화 서버다. 우리 구조에서 사람이 EC2에서 하던 일(빌드, deploy.sh 실행)을 GitLab 이벤트에 반응해 자동으로 수행하는 역할을 맡는다.
- **왜 컨테이너로 설치하나**: 호스트를 오염시키지 않고, Jenkins Home 볼륨(`/home/ubuntu/jenkins-data`)만 백업하면 설정 전체(잡, 플러그인, Credentials)가 보존·이설 가능하다. 설치 정의가 repo에 코드로 남는 것도 같은 장점이다.
- **docker.sock 마운트의 의미**: Jenkins 컨테이너 안에는 Docker 데몬이 없다. 대신 호스트의 `/var/run/docker.sock`을 마운트해서 **호스트의 Docker를 원격 조종**한다 — Jenkins가 빌드한 이미지가 호스트 로컬 저장소에 남고, deploy.sh가 그걸 바로 쓰는 우리 구조의 전제다.

> **보안 경고**: docker.sock을 가진 컨테이너는 사실상 호스트 root 권한과 같다. 그래서 검증되지 않은 코드(임의 브랜치의 Jenkinsfile 등)를 운영 Jenkins에서 실행하지 않는 것이 원칙이다. 파이프라인 설계(4단계)에서 이 원칙을 반영한다.

## 3. 설치 구성 (repo `deploy/jenkins/`)

| 파일 | 역할 |
| --- | --- |
| `Dockerfile` | 공식 `jenkins/jenkins:lts-jdk21`에 Docker CLI + Compose 플러그인 + ssh 클라이언트 추가 |
| `docker-compose.yml` | 실행 정의 — 포트, prefix, 볼륨, docker.sock |
| `.env.example` | `DOCKER_GID` 안내 (아래 참고) |

설계 포인트:

- **`127.0.0.1:8080` 바인딩** — 외부 직접 접근 불가. 모든 접근은 호스트 Nginx의 `/jenkins/` 경로를 거친다 (HTTPS + 배포 파이프라인과 같은 관문)
- **`--prefix=/jenkins`** — Nginx 경로, Jenkins URL 설정과 삼위일체로 일치해야 한다. 하나라도 다르면 리다이렉트가 꼬인다
- **`DOCKER_GID`** — 호스트 docker.sock 파일의 그룹 ID를 컨테이너의 jenkins 사용자에게 부여해 소켓 접근을 허용하는 장치. 호스트마다 값이 달라서 `.env`로 주입한다:
  ```bash
  echo "DOCKER_GID=$(stat -c '%g' /var/run/docker.sock)" > .env
  ```
- **Jenkins Home 볼륨** (`/home/ubuntu/jenkins-data`) — 잡, 플러그인, 사용자, Credentials 암호화 키 전부가 여기 있다. 백업은 이 디렉토리 전체를 일관된 시점에 뜬다 (일부 파일만 복사 금지, 백업본도 Secret으로 취급)

## 4. 설치 절차

```bash
# EC2에서 — 디렉토리 준비 (jenkins-data는 컨테이너 사용자 UID 1000 소유)
mkdir -p /home/ubuntu/jenkins /home/ubuntu/jenkins-data
sudo chown -R 1000:1000 /home/ubuntu/jenkins-data

# 본인 PC의 repo 루트에서 — 설치 정의 전송
scp -i <pem> deploy/jenkins/Dockerfile deploy/jenkins/docker-compose.yml ubuntu@j15b201.p.ssafy.io:/home/ubuntu/jenkins/

# EC2에서 — DOCKER_GID 기록 후 기동
cd /home/ubuntu/jenkins
echo "DOCKER_GID=$(stat -c '%g' /var/run/docker.sock)" > .env
docker compose up -d --build

# 확인 3종
curl -I http://127.0.0.1:8080/jenkins/       # 403 = 기동 OK (로그인 필요 상태)
docker exec jenkins docker version           # Client/Server 둘 다 나와야 함 (sock 권한)
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword   # 초기 비밀번호
```

Nginx의 `/jenkins/` 경로는 `deploy/nginx/itda.conf`에 정의되어 있으며, 반영 절차는 deploy.md 5.1절을 따른다.

## 5. 초기 설정 (브라우저)

1. `https://j15b201.p.ssafy.io/jenkins/` 접속 → 초기 비밀번호 입력 (Unlock)
2. **Install suggested plugins**
3. 관리자 계정 생성 — 팀 공용, 계정 정보는 팀 비공개 페이지에 기록
4. Jenkins URL: `https://j15b201.p.ssafy.io/jenkins/` 확인
5. 추가 플러그인 설치: Manage Jenkins → Plugins → Available에서 **GitLab** (Webhook 트리거와 빌드 상태 회신용 — suggested에 미포함)

## 6. Credentials 관리 기준

Credentials는 Manage Jenkins → Credentials → System → Global에 등록하며, **ID는 Jenkinsfile이 참조하는 계약**이므로 아래 표와 정확히 일치해야 한다. 원칙: 소비 주체별로 토큰을 분리한다 (하나가 만료·유출되어도 다른 쪽이 영향받지 않게).

| ID | Kind | 내용 | 상태 |
| --- | --- | --- | --- |
| `gitlab-repository-read` | Username with password (`oauth2` / 토큰) | Jenkins checkout용 GitLab 읽기 토큰 (`jenkins-repository-read`, read_repository) | ✅ 등록됨 |
| `gitlab-api-token` | GitLab API token | ssafy-gitlab 연결(커밋 상태 회신)용 api scope 토큰 | ✅ 등록됨 |
| `gitlab-api-token-text` | Secret text | 같은 토큰 값 — AI 리뷰 스크립트의 string 바인딩용 (플러그인 타입은 string 바인딩 불가) | ✅ 등록됨 |
| `gms-api-key` | Secret text | AI 코드리뷰의 GMS(LLM) 호출 키 | ✅ 등록됨 |
| `mattermost-webhook` | Secret text | 빌드·배포 알림 Webhook URL | 4단계에서 등록 |

GitLab 토큰은 만료일이 있다 — 발급 시 만료일을 팀 일정에 기록하고, 만료 증상(Jenkins checkout 인증 실패)을 기억해둘 것.

## 7. 운영: 백업과 업데이트

- **백업**: `/home/ubuntu/jenkins-data` 전체를 일관된 시점에 복사. Credentials 암호화 키가 포함되므로 백업본도 Secret으로 취급한다
- **Jenkins 업데이트**: 백업 후 `cd /home/ubuntu/jenkins && docker compose build --pull && docker compose up -d`
- Jenkins 컨테이너가 죽어도 서비스(web/backend/ai)는 영향 없다 — Jenkins는 배포를 "실행"할 뿐, 실행된 서비스와 독립적이다. 복구는 compose 재기동, 최악의 경우 jenkins-data 백업 복원
