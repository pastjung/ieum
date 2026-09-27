# EC2 배포 환경 구축

## 1. 목적

이 문서는 EC2 서버의 초기 세팅부터 HTTPS 적용, blue-green 수동 배포까지의 절차와 각 선택의 이유를 정의한다. 서버를 처음 만져보는 팀원도 이 문서만으로 구조를 이해하고 장애 시 대응할 수 있는 것을 목표로 한다.

- 도메인: `i-eum.site`(+`www.i-eum.site`) — 옛 도메인 `j15b201.p.ssafy.io`는 nginx가 `https://i-eum.site`로 301 넘긴다. 실시간 음성은 `openvidu.i-eum.site`
- 운영 DB: 외부 서버의 MariaDB — backend가 `DB_URL`로 연결 (첫 배포 전 스키마 확인: 6.3절)
- 운영 Redis: 같은 compose의 `redis` 서비스(컨테이너 `itda-redis`, 볼륨 `itda-redis-data`)
- 서버 접속: `ssh -i <pem파일> ubuntu@i-eum.site` (인증키는 저장소 밖에 보관, Git 커밋 금지)

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
    server_name i-eum.site www.i-eum.site;

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
curl -I http://i-eum.site    # HTTP 200 확인 후 진행

# 3) 발급 — 이메일은 팀이 실제 확인하는 주소 (갱신 실패 경고가 오는 곳)
sudo certbot --nginx -d i-eum.site -d www.i-eum.site

# 4) 자동 갱신 검증
sudo certbot renew --dry-run
systemctl status certbot.timer --no-pager   # active (waiting) 확인
```

인증서 유효기간은 90일이며 `certbot.timer`가 자동 갱신한다. 등록한 이메일로 만료 경고가 온다면 자동 갱신이 실패하고 있다는 뜻이므로 `sudo certbot renew` 로그를 확인한다.

현재 서버에는 인증서가 셋 있다: `i-eum.site`(+www), `openvidu.i-eum.site`, `j15b201.p.ssafy.io`(옛 도메인 넘김 전용). 셋 다 `certbot.timer`가 갱신하며, 설정을 바꾼 첫 배포 뒤에는 `sudo certbot renew --dry-run`이 세 인증서 모두 성공하는지 확인한다(10절). 옛 도메인은 80에서도 301을 돌려주므로 갱신 방식이 `webroot`이면 실패할 수 있다 — `/etc/letsencrypt/renewal/j15b201.p.ssafy.io.conf`의 `authenticator = nginx`를 확인한다.

## 5. 배포 파일 배치

EC2의 앱 디렉토리 구조. repo의 `deploy/`, `scripts/`가 원본이고, EC2에는 그 사본 + 운영 설정/상태 파일이 있다.

```text
/home/ubuntu/app/
├── docker-compose.prod.yml     # repo deploy/ 사본
├── .env.config                 # 운영 설정 (원본: Jenkins Secret file 'itda-env-config' — Sync가 배포, 키 목록 6.2.1)
├── .env.tags                   # 배포 태그 상태 (deploy.sh가 갱신 — 서버가 원본)
├── nginx/inc/                  # blue/green 활성 포트 (deploy.sh가 갱신)
│   ├── web.inc  backend.inc  ai.inc
├── downloads/                  # 학습자 앱 파일 (ieum.apk, latest.json, ieum-<직전>.apk — release-app.sh가 갱신, 9절)
└── scripts/                    # repo scripts/ 사본 (deploy.sh, cleanup-images.sh)
```

호스트 Nginx 쪽 파일 세 개는 root 영역에 배치한다.

| repo 파일 | EC2 배치 위치 | 역할 |
| --- | --- | --- |
| `deploy/nginx/itda.conf` | `/etc/nginx/sites-available/itda` | `i-eum.site` 라우팅 + SSL + `/app/` 앱 파일 + 옛 도메인 301 + AI 내부 리스너(9100) |
| `deploy/nginx/openvidu.conf` | `/etc/nginx/sites-available/openvidu.conf` | `openvidu.i-eum.site` → `127.0.0.1:7880`(OpenVidu) |
| `deploy/nginx/connection-upgrade.conf` | `/etc/nginx/conf.d/` | WebSocket Upgrade 공통 변수(map) |

초기 배치 절차 (최초 1회):

```bash
# EC2에서 — 디렉토리 생성
mkdir -p /home/ubuntu/app/nginx/inc /home/ubuntu/app/scripts /home/ubuntu/app/downloads

# 본인 PC의 repo 루트에서 — 파일 전송
scp -i <pem> deploy/docker-compose.prod.yml ubuntu@i-eum.site:/home/ubuntu/app/
scp -i <pem> deploy/nginx/inc/*.inc ubuntu@i-eum.site:/home/ubuntu/app/nginx/inc/
scp -i <pem> scripts/deploy.sh scripts/cleanup-images.sh ubuntu@i-eum.site:/home/ubuntu/app/scripts/
scp -i <pem> deploy/nginx/itda.conf deploy/nginx/openvidu.conf deploy/nginx/connection-upgrade.conf ubuntu@i-eum.site:/home/ubuntu/

# EC2에서 — Nginx 설정 배치 (certbot이 만든 임시 설정을 최종본으로 교체)
sudo mv ~/connection-upgrade.conf /etc/nginx/conf.d/
sudo cp ~/itda.conf /etc/nginx/sites-available/itda
sudo cp ~/openvidu.conf /etc/nginx/sites-available/openvidu.conf
sudo ln -sfn /etc/nginx/sites-available/openvidu.conf /etc/nginx/sites-enabled/openvidu.conf
sudo nginx -t                    # 반드시 통과 확인 후
sudo systemctl reload nginx
chmod +x /home/ubuntu/app/scripts/*.sh
```

이 시점에 `https://i-eum.site`는 **502를 반환하는 것이 정상**이다 — 라우팅은 동작하지만 컨테이너가 아직 없는 상태이며, 6절의 첫 배포로 해소된다.

AI 내부 리스너(9100)는 backend 컨테이너가 `host.docker.internal:9100`으로 AI를 호출할 때 활성 슬롯(blue/green)으로 라우팅해주는 통로다. 외부(인터넷)의 9100 접근은 UFW 기본 거부가 차단하고, 컨테이너 대역(172.16.0.0/12)만 3.1절의 UFW 규칙으로 허용한다.

### 5.1 Nginx 설정의 원본과 반영 절차

**원본은 항상 repo다.** EC2의 `/etc/nginx/sites-available/itda`는 repo `deploy/nginx/itda.conf`의 배포된 사본이며, **서버에서 직접 수정하지 않는다** — 서버에서 고치면 repo와 어긋나고(드리프트), 다음 반영 때 그 수정이 조용히 사라진다.

예외는 `nginx/inc/*.inc`다. 이 파일들은 deploy.sh가 배포 때마다 갱신하는 **상태 파일**이라 서버 쪽이 살아있는 원본이고, repo의 inc는 첫 배치용 초기값일 뿐이다. 즉 "설정은 repo가 원본, 상태(활성 슬롯)는 서버가 원본"으로 구분한다. deploy.sh는 inc만 덮어쓸 뿐 `itda` 파일은 절대 건드리지 않는다 — `itda`의 include 지시자가 reload 시점에 inc의 현재 내용을 읽어오는 구조다.

**설정 변경·반영 절차 (예: Jenkins 경로 주석 해제, location 추가):**

1. repo에서 `deploy/nginx/itda.conf` 수정 → MR → dev 병합
2. 서버에 반영:

```bash
# 본인 PC에서
scp -i <pem> deploy/nginx/itda.conf ubuntu@i-eum.site:/home/ubuntu/

# EC2에서
sudo cp ~/itda.conf /etc/nginx/sites-available/itda
sudo nginx -t                    # 문법 검증 — 실패 시 반영 전이므로 서비스 무영향
sudo systemctl reload nginx      # 무중단 반영
```

`nginx -t`가 안전장치다. 새 설정이 잘못돼도 검증에서 걸리고 기존 설정으로 계속 동작하며, reload는 진행 중인 연결을 끊지 않는다.

**자동화 완료 (4단계):** Jenkins 배포 파이프라인의 Sync 스테이지가 매 배포마다 위 반영 절차(사이트 파일 `itda`·`openvidu.conf`와 `connection-upgrade.conf`를 모두 백업 → 복사 → `nginx -t` 검증 → reload, 실패 시 셋 다 복원)를 자동 수행한다 — compose·scripts도 함께 동기화된다 (pipeline.md 5.1절). "repo 수정 → 병합 → release 배포 시 자동 반영"으로 닫혀 사람이 반영을 잊어 생기는 드리프트가 구조적으로 사라졌다. 설정을 무엇으로 바꿀지는 여전히 MR이 통제하며, 자동화된 것은 기계적인 반영 절차뿐이다. 이 절의 수동 절차는 최초 배치와 비상시(Jenkins 장애)용으로 유지한다.

## 6. 첫 수동 배포

Jenkins 없이 사람이 직접 하는 배포 절차. 4단계 자동화가 완성되어도 이 절차는 비상시 그대로 사용된다.

### 6.1 GitLab 클론 토큰

EC2가 코드를 클론하려면 Project Access Token이 필요하다. GitLab → Settings → Access Tokens에서 Role `Developer`, Scope `read_repository`만으로 발급한다. **만료일(기본 수 주)을 팀 일정에 기록해둘 것** — 만료되면 EC2의 `git pull`이 인증 에러로 실패한다.

```bash
git clone -b dev https://oauth2:<토큰>@lab.ssafy.com/s15-bigdata-recom-sub1/S15P21B201.git ~/src
```

토큰은 `~/src/.git/config`에 저장되므로 read 전용 토큰만 사용한다.

### 6.2 환경 파일 작성 (.env.config / .env.tags)

환경 파일은 **설정과 상태로 분리**되어 있다:

| 파일 | 내용 | 원본 |
| --- | --- | --- |
| `.env.config` | backend 설정 전체 + compose 치환값 (키 목록: 6.2.1절) | **Jenkins Secret file `itda-env-config`** — Sync 스테이지가 매 배포마다 서버로 반영. 서버에서 직접 수정하면 다음 배포 때 사라진다 (변경 절차: README 3.5절) |
| `.env.tags` | WEB/BACKEND/AI_TAG (현재 배포 태그) | **서버** — deploy.sh가 배포마다 갱신. 사람이 편집하지 않는다 |

최초 배치(파이프라인 구축 전 수동 배포) 시에만 둘 다 손으로 만든다:

`.env.config`는 6.2.1절 키 목록대로 작성한다(값은 사람만 다룬다).

```bash
# .env.tags — 자리 채우기 값으로 시작 (deploy.sh가 실제 값으로 갱신)
cat > /home/ubuntu/app/.env.tags <<'EOF'
WEB_TAG=none
BACKEND_TAG=none
AI_TAG=none
EOF
chmod 600 /home/ubuntu/app/.env.config /home/ubuntu/app/.env.tags
```

- `none`은 자리 채우기 값이다. compose가 두 파일의 필수 변수를 파싱 시점에 검사하므로, 사용하지 않는 값도 존재는 해야 한다.
- 점(.)으로 시작하는 숨김 파일이라 `ls -a`로만 보인다. 권한은 600(소유자만)을 유지한다.
- 파이프라인 구축 후에는 같은 내용을 Jenkins Secret file `itda-env-config`로도 등록해야 한다 — 그때부터 Jenkins 쪽이 원본이 된다.

#### 6.2.1 운영 설정 키 목록 (`.env.config` = Jenkins Secret file `itda-env-config`)

backend 컨테이너는 compose의 `env_file: .env.config`로 이 파일의 **모든 키**를 환경변수로 받는다. 필수 키가 빠지거나 형식이 틀리면 backend가 뜨지 않고 deploy.sh 헬스체크가 배포를 자동 취소한다(운영 트래픽은 직전 버전 유지). 값은 사람만 다루며 저장소에는 키 이름만(`deploy/.env.config.example`) 둔다.

| 구분 | 키 | 값·형식 | 필수 |
| --- | --- | --- | --- |
| compose 치환 | `REGISTRY_PREFIX` | 빈 값(로컬 이미지 저장소) | 선택 |
| backend 공통 | `SPRING_PROFILES_ACTIVE` | `prod` — **빠지면 local 프로필로 떠서 실패한다** (옛 `BACKEND_PROFILE`은 더 이상 읽지 않는다) | 필수 |
| | `AI_BASE_URL` | `http://host.docker.internal:9100/api/v1` (현 값 유지) | 필수 |
| DB | `DB_URL` | `jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC` | 필수 |
| | `DB_USERNAME`·`DB_PASSWORD` | 운영 계정 | 필수 |
| Redis | `REDIS_HOST` | `redis` (compose 서비스 이름) | 필수 |
| | `REDIS_PORT` | `6379` | 필수 |
| | `AUTH_REDIS_KEY_PREFIX` | `prod` — 실행 프로필과 다르면 기동 실패 | 필수 |
| 인증 토큰 | `AUTH_JWT_ISSUER` | `itda-backend-prod` — 프로필과 다르면 기동 실패 | 필수 |
| | `AUTH_ACCESS_ACTIVE_KID`·`AUTH_ACCESS_ACTIVE_KEY` | kid(영문·숫자·`._~-` 1~64자)·Base64URL 256비트 이상 — **운영용으로 새로 생성** | 필수 |
| | `AUTH_ACCESS_RETIRED_KEYS` | `kid:key,kid:key` 또는 빈 값 | 선택 |
| | `AUTH_REFRESH_ACTIVE_KID`·`AUTH_REFRESH_ACTIVE_KEY`·`AUTH_REFRESH_RETIRED_KEYS` | 위와 같은 형식, 별도 key | 필수(retired는 선택) |
| | `AUTH_ACTION_ACTIVE_KID`·`AUTH_ACTION_ACTIVE_KEY`·`AUTH_ACTION_RETIRED_KEYS` | 위와 같은 형식, 별도 key. kid는 세 ring 통틀어 유일 | 필수(retired는 선택) |
| 메일 | `MAIL_DISPATCH_ENABLED` | `true`(발송) / `false` | 필수 |
| | `MAIL_SMTP_HOST`·`MAIL_SMTP_PORT`·`MAIL_SMTP_SECURITY` | 제공자 값, 보안 방식 `SSL` 또는 `STARTTLS` | 필수 |
| | `MAIL_SMTP_USERNAME`·`MAIL_SMTP_PASSWORD` | 제공자 계정 | `MAIL_SMTP_SECURITY`가 `SSL`·`STARTTLS`면 필수(MailProviderProperties가 검사) |
| | `MAIL_FROM_ADDRESS` | 발신 주소 | 필수 |
| | `MAIL_FROM_NAME` | 표시 이름. **줄을 아예 빼면 기본값 `이음(I:EUM)`을 쓰고, `MAIL_FROM_NAME=`처럼 값을 비워 두면 그 빈 값이 기본값을 덮어써 표시 이름 없이 나간다** — 기본값을 쓰려면 줄 자체를 지우거나 주석 처리한다 | 선택(줄을 생략하거나 값을 채운다) |
| | `MAIL_REPLY_TO` | 회신 주소 | 선택 |
| | `MAIL_LINK_EMAIL_VERIFICATION_URL` | `https://i-eum.site/auth/verify-email` | 필수 |
| | `MAIL_LINK_PASSWORD_RESET_URL` | `https://i-eum.site/auth/reset-password` | 필수 |
| 음성 | `LIVEKIT_URL` | `https://openvidu.i-eum.site` | 필수 |
| | `LIVEKIT_API_KEY`·`LIVEKIT_API_SECRET` | `/opt/openvidu/config/openvidu.env`의 같은 이름 값 | 필수 |
| 음성 합성 | `CARTESIA_API_KEY` | Cartesia 키. 비우면 목소리 API만 503 | 선택 |

`deploy/.env.config.example`에 사람이 붙여 넣을 내용 (값 없이 키와 안내만):

```dotenv
# 운영 설정 — Jenkins Secret file 'itda-env-config'의 원본 구조 (docs/infra/deploy.md 6.2.1)
# 값에 $가 들어가면 값 전체를 작은따옴표로 감싼다 (compose가 $를 치환한다)
REGISTRY_PREFIX=
SPRING_PROFILES_ACTIVE=prod
AI_BASE_URL=http://host.docker.internal:9100/api/v1
DB_URL=jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC
DB_USERNAME=
DB_PASSWORD=
REDIS_HOST=redis
REDIS_PORT=6379
AUTH_REDIS_KEY_PREFIX=prod
AUTH_JWT_ISSUER=itda-backend-prod
AUTH_ACCESS_ACTIVE_KID=
AUTH_ACCESS_ACTIVE_KEY=
AUTH_ACCESS_RETIRED_KEYS=
AUTH_REFRESH_ACTIVE_KID=
AUTH_REFRESH_ACTIVE_KEY=
AUTH_REFRESH_RETIRED_KEYS=
AUTH_ACTION_ACTIVE_KID=
AUTH_ACTION_ACTIVE_KEY=
AUTH_ACTION_RETIRED_KEYS=
MAIL_DISPATCH_ENABLED=true
MAIL_SMTP_HOST=
MAIL_SMTP_PORT=
MAIL_SMTP_USERNAME=
MAIL_SMTP_PASSWORD=
MAIL_SMTP_SECURITY=STARTTLS
MAIL_FROM_ADDRESS=
# MAIL_FROM_NAME=   # 생략하면 기본값 이음(I:EUM). 값을 비운 채(=만) 두면 표시 이름 없이 나간다
MAIL_REPLY_TO=
MAIL_LINK_EMAIL_VERIFICATION_URL=https://i-eum.site/auth/verify-email
MAIL_LINK_PASSWORD_RESET_URL=https://i-eum.site/auth/reset-password
LIVEKIT_URL=https://openvidu.i-eum.site
LIVEKIT_API_KEY=
LIVEKIT_API_SECRET=
CARTESIA_API_KEY=
```

운영용 서명 key 만들기 (ring마다 한 번씩, 로컬 key 재사용 금지):

```bash
openssl rand 32 | base64 | tr '+/' '-_' | tr -d '=\n'; echo   # Base64URL 256비트
# kid 예: access-202609, refresh-202609, action-202609 (세 ring 사이에 겹치면 기동 실패)
```

작성 규칙: `KEY=값` 사이에 공백을 두지 않는다. 값에 `$`가 있으면 `'...'`로 감싼다 — 같은 파일을 deploy.sh가 compose `--env-file`로도 읽어 `$`를 변수로 치환하기 때문이다.

### 6.3 외부 DB 스키마 확인 (첫 배포 전, 사람이 실행 — 읽기 전용)

운영 프로필은 `ddl-auto: validate`라 외부 DB 스키마가 엔티티와 다르면 backend가 뜨지 않는다(헬스체크가 배포를 자동 취소). 첫 배포 전에 본인 PC에서 운영 DB를 대상으로 **검증만** 하는 기동을 한 번 해 본다. 계정은 **읽기 전용**을 쓴다 — 기동 중 쓰기가 일어나도 DB가 거부한다.

```bash
# 저장소 루트 — 로컬 Redis만 띄운다 (prod 프로필도 기동 시 Redis PING을 보낸다)
docker compose up -d redis
cd apps/backend
k() { openssl rand 32 | base64 | tr '+/' '-_' | tr -d '=\n'; }   # 이 확인용 1회성 key
SPRING_PROFILES_ACTIVE=prod SERVER_PORT=18080 \
DB_URL='jdbc:mariadb://<host>:<port>/<db>?connectionTimeZone=UTC' DB_USERNAME='<읽기전용 계정>' DB_PASSWORD='<비밀번호>' \
REDIS_HOST=localhost REDIS_PORT=6379 AUTH_REDIS_KEY_PREFIX=prod AUTH_JWT_ISSUER=itda-backend-prod \
AUTH_ACCESS_ACTIVE_KID=check-access AUTH_ACCESS_ACTIVE_KEY="$(k)" \
AUTH_REFRESH_ACTIVE_KID=check-refresh AUTH_REFRESH_ACTIVE_KEY="$(k)" \
AUTH_ACTION_ACTIVE_KID=check-action AUTH_ACTION_ACTIVE_KEY="$(k)" \
MAIL_DISPATCH_ENABLED=false MAIL_SMTP_HOST=localhost MAIL_SMTP_PORT=587 MAIL_SMTP_SECURITY=STARTTLS \
MAIL_FROM_ADDRESS=check@i-eum.site \
MAIL_LINK_EMAIL_VERIFICATION_URL=https://i-eum.site/auth/verify-email \
MAIL_LINK_PASSWORD_RESET_URL=https://i-eum.site/auth/reset-password \
LIVEKIT_URL=https://openvidu.i-eum.site LIVEKIT_API_KEY=check LIVEKIT_API_SECRET=check \
./gradlew bootRun
```

- `MAIL_DISPATCH_ENABLED=false`를 반드시 준다 — 켜 두면 운영 DB의 메일 대기열을 PC가 발송할 수 있다.
- 로그에 `Started ...Application`이 찍히면 스키마가 엔티티와 맞는다 → Ctrl+C.
- `Schema-validation: missing table [...]`·`missing column [...]`·`wrong column type`이면 맞지 않는다 → 첫 배포를 멈추고 팀과 반영 방법을 정한다(스키마 변경 도구 도입은 범위 밖).

`validate`는 UNIQUE 제약을 보지 않으므로 DB 클라이언트로 따로 확인한다:

```sql
SHOW CREATE TABLE lecture_problems;   -- UNIQUE KEY (lecture_id, problem_id)
SHOW CREATE TABLE problem_attempts;   -- UNIQUE KEY (lecture_id, user_id, problem_id)
```

### 6.4 빌드와 배포

```bash
cd ~/src && git pull
TAG=manual-$(git rev-parse --short HEAD)
docker build --target web -t itda-web:$TAG front
docker build -t itda-backend:$TAG apps/backend    # 첫 빌드는 5~10분
docker build -t itda-ai:$TAG apps/ai

cd /home/ubuntu/app
docker compose -f docker-compose.prod.yml --env-file .env.config --env-file .env.tags up -d redis   # 최초 1회 (Jenkins는 Redis 단계가 수행)
./scripts/deploy.sh web $TAG
./scripts/deploy.sh backend $TAG
./scripts/deploy.sh ai $TAG
```

확인:

```bash
curl -I https://i-eum.site                        # 200 (web)
curl https://i-eum.site/api/v1/health             # backend JSON
curl http://127.0.0.1:9100/api/v1/health          # ai (내부 리스너 경유)
docker exec itda-redis redis-cli ping              # PONG (운영 Redis)
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
2. **새 버전 기동** — `.env.tags`의 태그를 갱신하고 대상 슬롯 컨테이너를 기동한다. 이 동안 기존 슬롯이 모든 트래픽을 계속 받는다.
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

**롤백이 못 되돌리는 것: 데이터베이스.** 롤백은 코드(이미지)만 되돌린다. 새 버전이 이미 DB 스키마를 바꿨거나(컬럼 추가·삭제) 데이터를 변형했다면, 코드를 되돌려도 DB는 그대로다. 그래서 스키마를 바꾸는 배포는 "이전 버전 코드도 새 스키마에서 동작하는가"(하위 호환)를 릴리스 전에 확인해야 한다. DB 자체를 되돌리는 건 외부 DB 백업 복원이라는 별개의, 훨씬 무거운 작업이다(그 사이 쌓인 데이터 유실).

**롤백 후 할 일**: ① Mattermost/팀 채널에 롤백 사실과 현재 서비스 버전 공유 ② 실패한 버전의 컨테이너 로그 확보 (`docker logs`) ③ 원인 수정은 hotfix 절차(브랜치 전략 문서)로.

### 7.4 롤백 훈련

장애 상황에서 처음 해보지 않도록, 배포 구조를 바꿀 때마다 한 번씩 즉시 롤백을 훈련한다. 두 슬롯이 같은 외부 DB·Redis를 쓰므로 데이터로는 트래픽 이동을 구분할 수 없다 — 슬롯을 바꾼 뒤 `docker logs --since 1m itda-backend-<색>-1`에 요청 로그가 찍히는 쪽으로 확인한다.

1. 현재 활성 슬롯 확인: `cat /home/ubuntu/app/nginx/inc/backend.inc`
2. 7.3절 방법 1로 이전 슬롯으로 전환 → `curl https://i-eum.site/api/v1/health` 200
3. 이전 슬롯 컨테이너 로그에 방금 요청이 찍히는지 확인
4. 같은 방법으로 원래 슬롯으로 복귀


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
| 6.2 환경 파일 | `/home/ubuntu/app/.env.config`, `.env.tags` | 최초만 사람 | config는 Jenkins(itda-env-config)가 원본·Sync가 반영, tags는 deploy.sh가 갱신 |
| 6.4 빌드 | 로컬 Docker 이미지 (`itda-*:<태그>`) | docker build | 보존 정책(docker.md 5절)에 따라 정리 |
| 배포·롤백 시마다 | `nginx/inc/*.inc` (한 줄 상태 파일) | deploy.sh 또는 사람(롤백) | 매 전환마다 덮어써짐 |
| 5 초기 배치 / Sync | `/etc/nginx/sites-available/openvidu.conf` | 사람(최초) → Jenkins Sync | repo `deploy/nginx/openvidu.conf` 변경 시 재반영 |
| Sync | `/home/ubuntu/app/downloads/` | Jenkins Sync(`mkdir -p`) | 내용은 `release-app.sh`만 바꾼다 (9절) |
| Jenkins Redis 단계 | 컨테이너 `itda-redis`, 볼륨 `itda-redis-data` | `docker compose up -d redis` | 없음 (blue-green 대상 아님). 볼륨을 지우면 로그인 상태가 모두 사라진다 |

## 9. 학습자 앱(APK) 배포

APK는 PC에서 빌드해 두 곳에 올린다: **GitLab Releases**(기록·보관 — 프로젝트가 비공개라 학습자는 못 받음)와 **`https://i-eum.site/app`**(학습자 다운로드). 서버 배포 파이프라인은 `app-v*` 태그를 무시한다.

| 위치 | 내용 |
| --- | --- |
| GitLab 일반 패키지 `ieum-android/<version>/` | `ieum-<version>.apk`, `ieum-<version>.apk.sha256` |
| GitLab Release `app-v<version>` | 변경 내용 + versionCode + SHA-256, 위 두 파일 링크 |
| 서버 `/home/ubuntu/app/downloads/` | `ieum.apk`(최신), `latest.json`, `ieum-<직전 버전>.apk`(하나만) |

`latest.json` 형식 (앱의 새 버전 알림과 웹 다운로드 페이지가 읽는다):

```json
{ "version": "1.0.0", "versionCode": 1, "releasedAt": "2026-09-27T12:00:00Z", "sha256": "<hex>", "url": "https://i-eum.site/app/ieum.apk", "notes": "변경 내용 한두 줄" }
```

### 9.1 준비 (PC에서 한 번)

- 릴리스 keystore를 만들어 **저장소 밖**에 두고 백업한다 — 잃으면 이미 설치된 앱을 업데이트할 수 없다.
- `~/.gradle/gradle.properties`: `IEUM_UPLOAD_STORE_FILE`, `IEUM_UPLOAD_KEY_ALIAS`, `IEUM_UPLOAD_STORE_PASSWORD`, `IEUM_UPLOAD_KEY_PASSWORD` (없으면 release 빌드가 실패한다 — 디버그 키 서명 방지)
- `ANDROID_SDK_ROOT`(또는 `ANDROID_HOME`) 환경변수가 Android SDK 설치 경로를 가리켜야 한다 — 스크립트가 그 아래 `build-tools/<버전>/apksigner`로 서명을 확인한다(디버그 키 서명 방지의 2차 확인)
- `front/apps/mobile/.env.production` (git 제외): `EXPO_PUBLIC_API_URL=https://i-eum.site/api/v1`, `EXPO_PUBLIC_APP_RELEASE_URL=https://i-eum.site/app`. Cartesia 키는 넣지 않는다(backend가 대신 호출)
- GitLab 개인 액세스 토큰(scope `api`)을 발급해 **만료일을 팀 일정에 기록**한다. 파일에 저장하지 않고 실행할 때 환경변수로만 준다
- 서버 pem 파일 경로 (저장소 밖)

### 9.2 절차

1. `app.json`의 `expo.version`(예: `1.0.0`)과 `expo.android.versionCode`(릴리스마다 +1)를 함께 올리는 MR을 병합한다(브랜치 전략 7절의 `release/app-*` 흐름).
2. 병합된 main 커밋에 **GitLab 웹 UI로** `app-v<version>` 태그를 만든다.
3. PC에서 그 태그를 체크아웃하고 스크립트를 실행한다.

```bash
git fetch origin --tags && git checkout app-v1.0.0
export GITLAB_TOKEN='<개인 액세스 토큰>'
export IEUM_DEPLOY_KEY='C:/keys/<서버>.pem'
scripts/release-app.sh "첫 배포: 실시간 수업 입장, 목소리 기능"
```

스크립트 순서: 확인(작업 트리·태그·versionCode·`.env.production`·토큰·서버 폴더) → `pnpm --filter @ieum/mobile test`·`typecheck` → `expo prebuild -p android --clean` + `gradlew assembleRelease` → **서명 확인(`apksigner verify --print-certs`로 디버그 키가 아닌지 확인 — `ANDROID_SDK_ROOT`/`ANDROID_HOME`에서 build-tools를 찾는다)** → SHA-256 → `latest.json` → GitLab 패키지·Release → 서버에 `*.tmp`로 올린 뒤 해시를 다시 확인하고 `mv`로 교체(이전 `ieum.apk`는 `ieum-<직전 버전>.apk`로 하나만 남김) → 공개 `latest.json` 확인. 실패하면 그 자리에서 멈추고 끝난 단계를 출력한다. 서버 교체가 마지막이라 그 전에 멈추면 학습자가 받는 파일은 그대로다.

4. 확인: GitLab → Deploy → Releases에 `app-v<version>`, `https://i-eum.site/app`에 새 버전·배포일, 폰에서 설치 → 로그인 → 수업 입장.
5. APK에 Cartesia 키가 없는지: `unzip -p ieum.apk assets/index.android.bundle | strings | grep -c sk_car` → `0`.

다시 실행할 때: 서버 교체 단계 전(빌드·GitLab 업로드 도중)에 실패했다면 같은 명령을 그대로 다시 실행한다 — GitLab 패키지 파일과 Release가 이미 있으면 스크립트가 이를 감지해 다시 올리지 않고 건너뛴 뒤 서버 교체로 이어간다(같은 태그로 Release를 다시 만들려 해서 409로 거부되지 않는다). 새 버전으로 다시 릴리스하려면 versionCode를 올린다. 윈도우 빌드가 `Filename longer than 260 characters`로 죽으면 `docs/solutions/2026-09-24-windows-ninja-long-path.md`.

### 9.3 되돌리기

새 APK에 문제가 있으면 학습자에게 `https://i-eum.site/app/ieum-<직전 버전>.apk`를 안내한다. 같은 versionCode로 되돌려 설치할 수는 없으므로(안드로이드는 낮은 versionCode 설치를 거부) 기존 설치를 지우고 설치하거나, 고친 새 버전(versionCode +1)을 올린다.

## 10. 서버에서 사람이 할 일 (에이전트는 서버 설정을 바꾸지 않는다)

첫 배포(`release/0.1.0`) 전에 순서대로 한다. 서버 명령은 모두 사람이 실행한다.

1. **읽기 전용 사전 확인** — 6.3절 스키마 확인, `sudo certbot renew --dry-run`(세 인증서 모두 성공), `ls -l /etc/nginx/sites-enabled/`에 `itda`와 `openvidu.conf`(또는 같은 파일을 가리키는 링크)가 하나씩만 있는지.
2. **서버 사이트 파일과 저장소 비교** — Sync가 덮기 전에 차이가 없는지 본다. 본인 PC에서 `scp -i <pem> deploy/nginx/itda.conf deploy/nginx/openvidu.conf ubuntu@i-eum.site:/tmp/` 후 서버에서 `sudo diff /etc/nginx/sites-available/openvidu.conf /tmp/openvidu.conf`, `sudo diff /etc/nginx/sites-available/itda /tmp/itda.conf`. openvidu는 머리 주석 외에, itda는 주석·`/app/`·`11m`·옛 도메인 블록 외에 차이가 있으면 저장소를 먼저 고친다(MR). 끝나면 `rm /tmp/itda.conf /tmp/openvidu.conf`.
3. **`itda-env-config` 갱신** — 6.2.1절 키를 모두 넣은 새 파일로 Jenkins credential 교체(README 3.5절). `deploy/.env.config.example`도 6.2.1절 내용으로 MR 갱신.
4. **`downloads/` 권한** — nginx 작업 프로세스(`www-data`)가 파일을 읽을 수 있어야 한다. Ubuntu 24.04의 홈 디렉터리는 기본 `750`이라 막힐 수 있다.

   ```bash
   ls -ld /home/ubuntu /home/ubuntu/app /home/ubuntu/app/downloads
   echo '{}' > /home/ubuntu/app/downloads/perm-check.json
   sudo -u www-data cat /home/ubuntu/app/downloads/perm-check.json && echo READ-OK
   rm /home/ubuntu/app/downloads/perm-check.json
   ```

   `READ-OK`가 안 나오면 경로 통과 권한만 준다: `chmod o+x /home/ubuntu /home/ubuntu/app` (목록 보기는 허용하지 않는다). `downloads/`는 첫 배포 Sync가 만들므로 이 확인은 첫 배포 직후, 앱 배포 전에 한다.
5. **옛 도메인으로 된 주소 옮기기** — `j15b201.p.ssafy.io`는 301로 넘어가며, 301은 POST 본문을 잃을 수 있다. GitLab 웹훅 두 개(`pipeline.md` 7.5절)를 `https://i-eum.site/jenkins/project/...`로, Jenkins → System → Jenkins URL을 `https://i-eum.site/jenkins/`로 바꾼다. 바꾼 뒤 웹훅 Test → 200.
6. **OpenVidu Meet 끄기(권장)·MinIO 확인** — `openvidu.md` 8절. 수업이 없는 시간에 한다(OpenVidu 재시작).
7. **배포 후 확인** — spec §8 완료 기준: 헬스체크 200, 가입 메일 링크가 `i-eum.site`, 재로그인·토큰 갱신(Redis), 수업 음성, `curl -sI http://j15b201.p.ssafy.io/x` → `301` + `Location: https://i-eum.site/x`, `https://openvidu.i-eum.site` 유지.

   **첫 배포에서 backend 헬스체크가 실패하면**: Jenkinsfile의 Deploy 단계는 `web backend ai` 순서로 진행하므로, backend에서 멈추면 web은 이미 새 빌드로 전환되어 있고 backend는 아직 이전 버전(또는 기동 실패 상태)이다 — web·backend 버전이 어긋난 채로 트래픽을 받지 않도록 web도 즉시 되돌린다.

   ```bash
   # 예: web을 blue(3001)로 되돌리기 (7.3절 방법 1과 같은 방식)
   echo 'set $web_port 3001;' > /home/ubuntu/app/nginx/inc/web.inc
   sudo nginx -t && sudo systemctl reload nginx
   ```

   또는 Jenkins에서 `itda-rollback` 잡을 `web` 서비스·직전 태그로 실행해 같은 결과를 얻는다(pipeline.md 6절). Jenkinsfile의 Deploy 단계 서비스 순서(`web backend ai`)는 이 문서·Jenkinsfile 양쪽에서 바꾸지 않는다 — 바꾸려면 별도 검토가 필요하다.
