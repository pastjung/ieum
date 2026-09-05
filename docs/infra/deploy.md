# EC2 배포 환경 구축

## 1. 목적

이 문서는 EC2 서버의 초기 세팅부터 HTTPS 적용, blue-green 수동 배포까지의 절차와 각 선택의 이유를 정의한다. 서버를 처음 만져보는 팀원도 이 문서만으로 구조를 이해하고 장애 시 대응할 수 있는 것을 목표로 한다.

- 도메인: `j15b201.p.ssafy.io`
- 운영 DB: AWS RDS(MySQL) — 별도 생성 (생성 전까지 backend는 배포 보류)
- 서버 접속: `ssh -i <pem파일> ubuntu@j15b201.p.ssafy.io` (인증키는 저장소 밖에 보관, Git 커밋 금지)

### 1.1 서버 사양과 용량 계획

SSAFY 지급 EC2의 확인된 사양 (`nproc`, `free -h`, `df -h`로 확인).

| 항목 | 값 | 비고 |
| --- | --- | --- |
| OS | Ubuntu 24.04 LTS (noble) | |
| CPU | 4 vCPU | |
| 메모리 | 15GB + 스왑 4GB | 스왑은 3.3절에서 추가 |
| 디스크 | 309GB (초기 사용 2.7GB) | 이미지 보존 정책(docker.md)에 충분 |
| 네트워크 | 공인 IP + 도메인, 클라우드 방화벽 22/80/443/1024~65535 | |

이 한 대에 올라갈 것 전부와 대략의 메모리 예산:

| 구성 요소 | 예상 상시 메모리 | 비고 |
| --- | --- | --- |
| backend blue/green (JVM ×2) | 1~2GB | 전환 직후 잠시 2개 동시 가동 |
| ai blue/green (uvicorn ×2) | 0.5~2GB | 모델 탑재 후 증가 예상 |
| web blue/green (nginx ×2) | 0.1GB 미만 | |
| Jenkins (3단계) | 1~2GB | 빌드 중 Gradle이 추가로 1~2GB 피크 |
| 모니터링 스택 (5단계) | 1GB 내외 | Prometheus + Grafana + exporter |
| 호스트 (OS, nginx, docker) | 1GB 내외 | |

합계 상시 5~8GB + 빌드 피크 2~4GB로, 15GB 안에서 여유 있게 운용 가능하다. 주의할 시나리오는 하나 — 릴리스 날 세 서비스 빌드가 동시에 돌면서 blue/green이 겹치는 순간의 피크이며, 이때를 위한 완충재가 스왑(3.3절)이다. 메모리 상황은 `free -h`, 컨테이너별 사용량은 `docker stats`로 확인한다.

## 2. 사전 지식: HTTPS, Let's Encrypt, Certbot

처음 읽는 사람을 위한 최소한의 배경.

- **HTTPS와 인증서**: HTTPS는 브라우저와 서버 사이 통신을 암호화한다. 이를 위해 서버는 "이 도메인의 주인이 맞다"는 **인증서**(신분증)가 필요하고, 브라우저는 신뢰할 수 있는 발급 기관이 서명한 인증서만 인정한다.
- **Let's Encrypt**: 인증서를 **무료로, 자동으로** 발급해주는 비영리 인증 기관. 원래 인증서는 유료(연 수만~수십만 원)였는데, 웹 전체의 HTTPS화를 위해 만들어졌다. 유효기간이 90일로 짧은 대신 자동 갱신을 전제로 설계됐다.
- **Certbot**: Let's Encrypt에서 인증서를 받아오는 공식 클라이언트 도구. `--nginx` 플러그인을 쓰면 발급뿐 아니라 **nginx 설정에 SSL 부분을 자동으로 써넣고**, 자동 갱신 스케줄(`certbot.timer`, 하루 2회 확인)까지 등록해준다.
- **발급 원리 (HTTP-01 챌린지)**: Let's Encrypt 서버가 우리 서버의 80 포트로 직접 접속해서 "정말 이 도메인을 제어하고 있는지" 확인한다. 그래서 발급 시점에 DNS가 서버를 가리키고, 80 포트가 열려 있고, nginx가 응답해야 한다.

## 3. EC2 초기 세팅

### 3.1 방화벽 (UFW) — 3중 방어 구조

SSAFY 정책상 UFW는 항상 활성 상태를 유지한다. 우리가 여는 포트는 셋뿐이다.

```bash
sudo ufw allow 80/tcp     # HTTP (HTTPS 리다이렉트 + 인증서 갱신용)
sudo ufw allow 443/tcp    # HTTPS (모든 서비스 트래픽)
# 22는 기본 허용되어 있음
sudo ufw allow from 172.16.0.0/12 to any port 9100 proto tcp   # AI 내부 리스너 (아래 5절)
```

내부 서비스 포트(3001/3002, 8081/8082, 8101/8102, Jenkins 8080)는 UFW에 추가하지 않는다. 다음 3중 방어가 겹쳐 있기 때문이다.

| 층 | 역할 |
| --- | --- |
| 클라우드 방화벽 (SSAFY) | 22, 80, 443, 1024~65535만 통과 |
| UFW | 기본 거부 + 위에서 허용한 것만 통과 |
| 127.0.0.1 바인딩 | 컨테이너 포트가 루프백에만 열려 외부 인터페이스에 아예 노출되지 않음 |

**주의: Docker는 UFW를 우회한다.** Docker가 iptables를 직접 조작하므로, compose에서 `"8080:8080"`처럼 공개 바인딩하면 UFW가 못 막는다. 그래서 모든 포트 바인딩에 반드시 `127.0.0.1:`을 붙이는 것이 규칙이다 (`deploy/docker-compose.prod.yml` 참고).

### 3.2 호스트 Nginx, Certbot, Docker 설치

먼저 이 아키텍처의 관문인 **호스트 Nginx**와 인증서 도구를 설치한다. 호스트 Nginx는 80/443에서 모든 외부 요청을 받아 SSL을 해제하고 서비스 컨테이너로 라우팅하는 리버스 프록시이며, blue-green 전환(4절, 7절)도 여기서 일어난다. web 컨테이너 안에도 nginx가 있지만 그건 정적 파일 서빙 전용으로 역할이 다르다 — 두 Nginx의 관계는 docker.md 7.1절 참고.

```bash
sudo apt update
sudo apt install -y ca-certificates curl git nginx certbot python3-certbot-nginx
```

설치 직후 nginx는 자동으로 실행되며(`systemctl status nginx`로 확인) 80 포트에 우분투 기본 페이지를 서빙하는 상태가 된다. 이 기본 사이트는 4.2절에서 제거한다.

이어서 Docker를 공식 저장소에서 설치한다 (Ubuntu 기본 저장소보다 최신, compose 플러그인 포함).

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" | sudo tee /etc/apt/sources.list.d/docker.list

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu    # 적용하려면 SSH 재접속
```

### 3.3 스왑 4GB

메모리가 바닥나면 리눅스의 OOM Killer가 메모리를 가장 많이 쓰는 프로세스(대개 JVM = 운영 중인 backend)를 강제 종료한다. 스왑은 순간 피크 때 디스크로 넘쳐 버티게 해주는 완충재다. RAM 15GB로 여유가 있지만, 한 서버에 서비스 6컨테이너 + Jenkins 빌드 + 모니터링이 겹치는 릴리스 날의 보험으로 잡아둔다.

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile          # 메모리 내용이 담기므로 root만 접근
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # 재부팅 유지
```

정상 상태에서 스왑 사용량은 0에 가깝다. 스왑을 상시 사용 중이면 메모리 부족 신호이니 원인을 찾는다.

## 4. HTTPS 발급

### 4.1 왜 임시 서버 블록을 먼저 만드나

`sudo certbot --nginx -d 도메인` 한 줄로도 발급이 되는 경우가 많다. 그런데도 아래처럼 임시 블록 → curl 확인 → certbot 순서를 쓰는 이유는 두 가지다.

**① SSL 설정이 들어갈 파일을 우리가 정하기 위해.** 인증서 파일 자체는 항상 `/etc/letsencrypt/live/<도메인>/`에 저장되지만, certbot은 추가로 **nginx 설정 파일을 찾아 SSL 설정을 자동으로 써넣는다**. 이때 대상은 "도메인과 일치하는 `server_name`이 있는 블록"이다. 일치하는 블록이 없으면 배포판 기본 파일을 건드리거나 실패한다. 임시 블록을 우리 파일(`sites-available/itda`)에 만들어두면 SSL 설정이 정확히 그 파일에 들어가고, 이후 같은 파일을 최종 라우팅 설정으로 교체하면서 SSL 부분을 그대로 이어받는다.

**② 발급 시도 전에 통신 경로를 검증하기 위해.** 발급은 Let's Encrypt가 우리 서버 80 포트로 접속해오는 과정이다(2절의 HTTP-01). DNS·방화벽·nginx 중 하나라도 어긋난 채 반복 시도하면 실패 원인 구분도 어렵고, **시간당 실패 5회를 넘기면 rate limit에 걸려 한동안 발급이 잠긴다**. `curl -I`로 미리 200을 확인하는 것은 이 최악의 시나리오를 막는 30초짜리 사전 점검이다.

비유하면: 인증서 발급은 우체국(Let's Encrypt)이 "이 주소에 진짜 사는지" 확인하러 오는 등기우편이다. 임시 블록은 **문패**를 다는 것이고, curl은 우체부가 오기 전에 **초인종이 울리는지 직접 눌러보는 것**이다.

### 4.2 발급 절차

```bash
# 1) 임시 서버 블록 (문패)
sudo tee /etc/nginx/sites-available/itda > /dev/null <<'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name j15b201.p.ssafy.io;

    location / {
        return 200 "certificate bootstrap\n";
        add_header Content-Type text/plain;
    }
}
EOF
sudo ln -sfn /etc/nginx/sites-available/itda /etc/nginx/sites-enabled/itda
sudo rm -f /etc/nginx/sites-enabled/default   # 기본 사이트가 도메인을 가로채지 않게
sudo nginx -t && sudo systemctl reload nginx

# 2) 사전 점검 (초인종)
curl -I http://j15b201.p.ssafy.io    # HTTP 200 확인 후 진행

# 3) 발급 — 이메일은 팀이 실제 확인하는 주소 (갱신 실패 경고가 오는 곳)
sudo certbot --nginx -d j15b201.p.ssafy.io

# 4) 자동 갱신 검증
sudo certbot renew --dry-run
systemctl status certbot.timer --no-pager   # active (waiting) 확인
```

인증서 유효기간은 90일이며 `certbot.timer`가 자동 갱신한다. 등록한 이메일로 만료 경고가 온다면 자동 갱신이 실패하고 있다는 뜻이므로 `sudo certbot renew` 로그를 확인한다.

## 5. 배포 파일 배치

EC2의 앱 디렉토리 구조. repo의 `deploy/`, `scripts/`가 원본이고, EC2에는 그 사본 + 서버에만 존재하는 `.env`가 있다.

```text
/home/ubuntu/app/
├── docker-compose.prod.yml     # repo deploy/ 사본
├── .env                        # 서버에만 존재 (태그, RDS 접속 정보 — Git 금지)
├── nginx/inc/                  # blue/green 활성 포트 (deploy.sh가 갱신)
│   ├── web.inc  backend.inc  ai.inc
└── scripts/                    # repo scripts/ 사본 (deploy.sh, cleanup-images.sh)
```

호스트 Nginx 쪽 파일 두 개는 root 영역에 배치한다.

| repo 파일 | EC2 배치 위치 | 역할 |
| --- | --- | --- |
| `deploy/nginx/itda.conf` | `/etc/nginx/sites-available/itda` | 라우팅 + SSL + AI 내부 리스너(9100) |
| `deploy/nginx/connection-upgrade.conf` | `/etc/nginx/conf.d/` | WebSocket Upgrade 공통 변수(map) |

초기 배치 절차 (최초 1회):

```bash
# EC2에서 — 디렉토리 생성
mkdir -p /home/ubuntu/app/nginx/inc /home/ubuntu/app/scripts

# 본인 PC의 repo 루트에서 — 파일 전송
scp -i <pem> deploy/docker-compose.prod.yml ubuntu@j15b201.p.ssafy.io:/home/ubuntu/app/
scp -i <pem> deploy/nginx/inc/*.inc ubuntu@j15b201.p.ssafy.io:/home/ubuntu/app/nginx/inc/
scp -i <pem> scripts/deploy.sh scripts/cleanup-images.sh ubuntu@j15b201.p.ssafy.io:/home/ubuntu/app/scripts/
scp -i <pem> deploy/nginx/itda.conf deploy/nginx/connection-upgrade.conf ubuntu@j15b201.p.ssafy.io:/home/ubuntu/

# EC2에서 — Nginx 설정 배치 (certbot이 만든 임시 설정을 최종본으로 교체)
sudo mv ~/connection-upgrade.conf /etc/nginx/conf.d/
sudo cp ~/itda.conf /etc/nginx/sites-available/itda
sudo nginx -t                    # 반드시 통과 확인 후
sudo systemctl reload nginx
chmod +x /home/ubuntu/app/scripts/*.sh
```

이 시점에 `https://j15b201.p.ssafy.io`는 **502를 반환하는 것이 정상**이다 — 라우팅은 동작하지만 컨테이너가 아직 없는 상태이며, 6절의 첫 배포로 해소된다.

AI 내부 리스너(9100)는 backend 컨테이너가 `host.docker.internal:9100`으로 AI를 호출할 때 활성 슬롯(blue/green)으로 라우팅해주는 통로다. 외부(인터넷)의 9100 접근은 UFW 기본 거부가 차단하고, 컨테이너 대역(172.16.0.0/12)만 3.1절의 UFW 규칙으로 허용한다.

### 5.1 Nginx 설정의 원본과 반영 절차

**원본은 항상 repo다.** EC2의 `/etc/nginx/sites-available/itda`는 repo `deploy/nginx/itda.conf`의 배포된 사본이며, **서버에서 직접 수정하지 않는다** — 서버에서 고치면 repo와 어긋나고(드리프트), 다음 반영 때 그 수정이 조용히 사라진다.

예외는 `nginx/inc/*.inc`다. 이 파일들은 deploy.sh가 배포 때마다 갱신하는 **상태 파일**이라 서버 쪽이 살아있는 원본이고, repo의 inc는 첫 배치용 초기값일 뿐이다. 즉 "설정은 repo가 원본, 상태(활성 슬롯)는 서버가 원본"으로 구분한다. deploy.sh는 inc만 덮어쓸 뿐 `itda` 파일은 절대 건드리지 않는다 — `itda`의 include 지시자가 reload 시점에 inc의 현재 내용을 읽어오는 구조다.

**설정 변경·반영 절차 (예: Jenkins 경로 주석 해제, location 추가):**

1. repo에서 `deploy/nginx/itda.conf` 수정 → MR → dev 병합
2. 서버에 반영:

```bash
# 본인 PC에서
scp -i <pem> deploy/nginx/itda.conf ubuntu@j15b201.p.ssafy.io:/home/ubuntu/

# EC2에서
sudo cp ~/itda.conf /etc/nginx/sites-available/itda
sudo nginx -t                    # 문법 검증 — 실패 시 반영 전이므로 서비스 무영향
sudo systemctl reload nginx      # 무중단 반영
```

`nginx -t`가 안전장치다. 새 설정이 잘못돼도 검증에서 걸리고 기존 설정으로 계속 동작하며, reload는 진행 중인 연결을 끊지 않는다.

**자동화 완료 (4단계):** Jenkins 배포 파이프라인의 Sync 스테이지가 매 배포마다 위 반영 절차(복사 → `nginx -t` 검증 → reload, 실패 시 이전 설정 복원)를 자동 수행한다 — compose·scripts도 함께 동기화된다 (pipeline.md 5.1절). "repo 수정 → 병합 → release 배포 시 자동 반영"으로 닫혀 사람이 반영을 잊어 생기는 드리프트가 구조적으로 사라졌다. 설정을 무엇으로 바꿀지는 여전히 MR이 통제하며, 자동화된 것은 기계적인 반영 절차뿐이다. 이 절의 수동 절차는 최초 배치와 비상시(Jenkins 장애)용으로 유지한다.

## 6. 첫 수동 배포

Jenkins 없이 사람이 직접 하는 배포 절차. 4단계 자동화가 완성되어도 이 절차는 비상시 그대로 사용된다.

### 6.1 GitLab 클론 토큰

EC2가 코드를 클론하려면 Project Access Token이 필요하다. GitLab → Settings → Access Tokens에서 Role `Developer`, Scope `read_repository`만으로 발급한다. **만료일(기본 수 주)을 팀 일정에 기록해둘 것** — 만료되면 EC2의 `git pull`이 인증 에러로 실패한다.

```bash
git clone -b dev https://oauth2:<토큰>@lab.ssafy.com/s15-bigdata-recom-sub1/S15P21B201.git ~/src
```

토큰은 `~/src/.git/config`에 저장되므로 read 전용 토큰만 사용한다.

### 6.2 .env 작성

```bash
cat > /home/ubuntu/app/.env <<'EOF'
REGISTRY_PREFIX=
WEB_TAG=none
BACKEND_TAG=none
AI_TAG=none
BACKEND_PROFILE=local
DB_URL=pending
DB_USERNAME=pending
DB_PASSWORD=pending
AI_BASE_URL=http://host.docker.internal:9100/api/v1
EOF
chmod 600 /home/ubuntu/app/.env
```

- `none`/`pending`은 자리 채우기 값이다. compose가 파일 전체의 필수 변수를 파싱 시점에 검사하므로, 사용하지 않는 서비스의 값도 존재는 해야 한다. 태그는 deploy.sh가 실제 값으로 갱신한다.
- `.env`는 점(.)으로 시작하는 숨김 파일이라 `ls -a`로만 보인다. 권한은 600(소유자만)을 유지한다.

### 6.3 backend의 임시 H2 운용 (RDS 연결 전)

`BACKEND_PROFILE=local`은 backend를 H2 in-memory로 띄운다. **두 가지 한계를 알고 사용한다.**

1. 인메모리라 재배포·재시작마다 데이터가 전부 사라진다.
2. blue/green 두 슬롯이 각자 별도의 인메모리 DB를 가진다 — 전환 시 데이터가 이어지지 않는다.

즉 API 동작 확인용이지 데이터를 쌓는 용도가 아니다. RDS 연결 시 전환 방법: `.env`에서 `BACKEND_PROFILE=prod`로 바꾸고 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`에 실제 값을 넣은 뒤 backend를 재배포한다. 이때 프로필을 잘못 설정하면 기동 로그에 `Unable to determine Dialect without JDBC metadata` 에러가 나며 헬스체크에서 자동 취소된다.

### 6.4 빌드와 배포

```bash
cd ~/src && git pull
TAG=manual-$(git rev-parse --short HEAD)
docker build -t itda-web:$TAG apps/web
docker build -t itda-backend:$TAG apps/backend    # 첫 빌드는 5~10분
docker build -t itda-ai:$TAG apps/ai

cd /home/ubuntu/app
./scripts/deploy.sh web $TAG
./scripts/deploy.sh backend $TAG
./scripts/deploy.sh ai $TAG
```

확인:

```bash
curl -I https://j15b201.p.ssafy.io                # 200 (web)
curl https://j15b201.p.ssafy.io/api/v1/health     # backend JSON
curl http://127.0.0.1:9100/api/v1/health          # ai (내부 리스너 경유)
docker ps
```

## 7. blue-green 배포와 롤백 운영

### 7.1 무중단 배포와 blue-green이란

**무중단 배포**는 새 버전을 반영하는 동안 사용자 요청이 한 건도 끊기지 않게 하는 배포 방식이다. 반대 개념부터 보면 이해가 쉽다 — 가장 단순한 배포는 "기존 컨테이너를 내리고 새 컨테이너를 올리는 것"인데, 내린 순간부터 새 버전이 뜰 때까지(Spring Boot 기준 수십 초) 모든 요청이 실패한다. 수업 중인 사용자가 있는 서비스에서는 허용하기 어렵다.

**blue-green**은 무중단을 구현하는 대표 전략이다. 같은 서비스의 자리(슬롯)를 두 개 두고:

```text
배포 전:   Nginx ──→ blue(구버전, 트래픽 100%)     green(비어 있음)
기동:      Nginx ──→ blue(트래픽 100%)             green(신버전 기동·검증 중)
전환:      Nginx ──→ green(신버전, 트래픽 100%)    blue(대기)
```

핵심 성질 세 가지:

1. **전환 전까지 신버전은 트래픽을 받지 않는다.** 기동과 헬스체크가 끝난, 검증된 상태에서만 전환한다. 신버전이 뜨다 죽어도 사용자는 아무것도 못 느낀다.
2. **전환은 원자적이다.** Nginx가 가리키는 포트를 바꾸고 reload하는 한 번의 동작이며, reload는 진행 중인 연결을 끊지 않는다.
3. **구버전 슬롯은 전환 후에도 살려둔다.** 신버전에서 문제가 발견되면 포트를 되돌리는 것만으로 수 초 만에 복귀한다. 구버전은 다음 배포 때 새 버전으로 교체된다.

그래서 `docker ps`에 같은 서비스 컨테이너가 **2개 떠 있는 것이 정상 상태**다. 트래픽은 inc 파일이 가리키는 쪽 하나만 받고, 다른 하나는 즉시 롤백 대기조다(요청이 없으므로 CPU 사용은 거의 0, 메모리만 점유). 이 "슬롯 2개 동시 가동"은 1.1절 용량 계획에 반영되어 있다.

참고로 무중단 전략에는 blue-green 외에도 rolling(인스턴스를 여러 개 두고 하나씩 교체 — Kubernetes 기본)과 canary(신버전에 트래픽 일부만 먼저 배분)가 있다. 단일 서버 + Nginx 환경에서는 blue-green이 구현이 가장 단순하고 롤백이 가장 빠르다.

### 7.2 deploy.sh 동작 원리

`./deploy.sh <service> <tag>` 한 번은 다음 4단계를 수행한다.

1. **활성 슬롯 판별** — `nginx/inc/<service>.inc`의 현재 포트를 읽어 반대 슬롯을 배포 대상으로 정한다.
2. **새 버전 기동** — `.env`의 태그를 갱신하고 대상 슬롯 컨테이너를 기동한다. 이 동안 기존 슬롯이 모든 트래픽을 계속 받는다.
3. **헬스체크 폴링** — 대상 슬롯의 헬스 엔드포인트를 3초 간격, 기본 90초까지 확인한다. **실패하면 대상 컨테이너의 로그를 출력하고 정지시킨 뒤 배포를 취소한다 — 운영 트래픽은 무영향.** (AI 모델 로딩이 길어지면 `HEALTH_TIMEOUT=300 ./deploy.sh ai <tag>` 처럼 조정)
4. **트래픽 전환** — inc 파일 포트를 바꾸고 `nginx -t` 검증 후 reload. reload는 기존 연결을 끊지 않는다. **이전 슬롯은 정지하지 않고 유지한다** (즉시 롤백용, 다음 배포 때 교체됨).

### 7.3 롤백

롤백은 "문제가 있는 새 버전에서 검증된 이전 버전으로 되돌리는 것"이다. 원인 분석보다 복구가 먼저다 — 서비스를 살려놓고 나서 원인을 찾는다.

**방법 1: 즉시 롤백 (Nginx 되돌리기) — 수 초.** 배포 직후 문제를 발견한 경우. 이전 슬롯이 아직 떠 있으므로 Nginx가 가리키는 포트만 되돌린다.

```bash
# 예: backend를 green(8082)으로 되돌리기
echo 'set $backend_port 8082;' > /home/ubuntu/app/nginx/inc/backend.inc
sudo nginx -t && sudo systemctl reload nginx
```

전제: 이전 슬롯 컨테이너가 살아 있어야 한다 (deploy.sh가 살려두는 이유).

**방법 2: 태그 롤백 (재배포) — 1~2분.** 시간이 지나 이전 슬롯도 새 버전으로 교체됐거나, 몇 버전 전으로 돌아가야 하는 경우. 보관된 이미지로 일반 배포와 같은 경로를 탄다.

```bash
docker images | grep itda-backend        # 보관된 태그 확인
./scripts/deploy.sh backend v1.1.0       # 이전 태그로 재배포
```

전제: 이미지 보존 정책(docker.md 5절). `docker image prune -a`를 운영 서버에서 실행하면 롤백 지점이 사라진다.

**롤백이 못 되돌리는 것: 데이터베이스.** 롤백은 코드(이미지)만 되돌린다. 새 버전이 이미 DB 스키마를 바꿨거나(컬럼 추가·삭제) 데이터를 변형했다면, 코드를 되돌려도 DB는 그대로다. 그래서 스키마를 바꾸는 배포는 "이전 버전 코드도 새 스키마에서 동작하는가"(하위 호환)를 릴리스 전에 확인해야 한다. DB 자체를 되돌리는 건 RDS 스냅샷 복원이라는 별개의, 훨씬 무거운 작업이다(그 사이 쌓인 데이터 유실).

**롤백 후 할 일**: ① Mattermost/팀 채널에 롤백 사실과 현재 서비스 버전 공유 ② 실패한 버전의 컨테이너 로그 확보 (`docker logs`) ③ 원인 수정은 hotfix 절차(브랜치 전략 문서)로.

### 7.4 롤백 훈련

장애 상황에서 처음 해보지 않도록, 배포 구조를 바꿀 때마다 한 번씩 즉시 롤백을 훈련한다. H2 임시 운용 중에는 두 슬롯의 DB가 분리된 점을 이용해 트래픽 이동을 눈으로 검증할 수 있다.

1. 활성 슬롯에 테스트 계정 가입 → 성공 (활성 슬롯 DB에 기록됨)
2. 같은 요청 반복 → 중복 에러 (기록 확인)
3. 방법 1로 이전 슬롯으로 전환
4. 같은 요청 반복 → **다시 성공하면 트래픽이 실제로 이전 슬롯(별도 DB)으로 이동한 것**
5. 원래 슬롯으로 복귀 (같은 방법으로 포트 원복)

RDS 연결 후에는 두 슬롯이 같은 DB를 공유하므로 이 방식은 쓸 수 없다 — 그때는 버전 응답(health의 버전 필드 등)으로 확인한다.


## 8. 단계별 생성·변경 파일 요약

어느 단계에서 어떤 파일이 생기고 바뀌는지의 전체 지도. 문제가 생겼을 때 "이 파일이 왜 이 상태지?"의 답을 여기서 찾는다.

| 단계 | 파일 | 만든 주체 | 이후 변경 |
| --- | --- | --- | --- |
| 3.2 패키지 설치 | `/etc/nginx/` 전체, `sites-enabled/default` | apt (nginx 설치) | default는 4.2에서 삭제 |
| 3.3 스왑 | `/swapfile`, `/etc/fstab` 한 줄 | 사람 | 없음 |
| 4.2 임시 블록 | `/etc/nginx/sites-available/itda` (+ sites-enabled 링크) | 사람 | certbot이 SSL 추가 → 5절에서 최종본으로 교체 |
| 4.2 certbot | `/etc/letsencrypt/live/<도메인>/` 인증서 일체 | certbot | certbot.timer가 자동 갱신 |
| 5 초기 배치 | `/home/ubuntu/app/` 전체 (compose, inc, deploy.sh), `/etc/nginx/conf.d/connection-upgrade.conf`, `sites-available/itda` 교체 | 사람 (repo에서 복사) | itda·compose·deploy.sh는 repo 변경 시 재반영(5.1), inc는 아래 |
| 6.1 클론 | `~/src/` (소스 저장소) | git | `git pull`로 갱신 |
| 6.2 .env | `/home/ubuntu/app/.env` | 사람 (서버에만 존재) | 태그는 deploy.sh가, DB·프로필 값은 사람이 갱신 |
| 6.4 빌드 | 로컬 Docker 이미지 (`itda-*:<태그>`) | docker build | 보존 정책(docker.md 5절)에 따라 정리 |
| 배포·롤백 시마다 | `nginx/inc/*.inc` (한 줄 상태 파일) | deploy.sh 또는 사람(롤백) | 매 전환마다 덮어써짐 |
