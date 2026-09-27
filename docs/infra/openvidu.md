# OpenVidu (실시간 수업 음성)

실시간 수업의 강사 → 학습자 단방향 음성은 EC2에 설치한 **OpenVidu 3.8.0 Community 단일 노드**(LiveKit 기반)가 중계한다.
backend는 음성 토큰 발급과 방 생성·삭제만 하고, 음성 데이터는 브라우저·앱과 OpenVidu 사이를 직접 오간다.
API 계약은 [docs/product/api-spec.md](../product/api-spec.md) 8.5절을 따른다.

> 서버 nginx의 `openvidu.i-eum.site` 블록은 **repo `deploy/nginx/openvidu.conf`가 원본**이며 배포 Sync가 매번 반영한다.
> OpenVidu 설치본(`/opt/openvidu`), ufw 규칙, `livekit.yaml` 변경은 repo가 관리하지 않는다 — 서버를 새로 만들면 이 문서대로 사람이 다시 해야 한다.

## 1. 구성

```
브라우저·앱 ──wss(443)──▶ 호스트 nginx ──http──▶ caddy(127.0.0.1:7880) ──▶ livekit-server   [신호]
브라우저·앱 ◀───── UDP 50000-60000 / TCP 7881 ─────▶ livekit-server                        [음성]
브라우저·앱 ◀───── UDP 443 / TCP 5349 (turn.i-eum.site) ─────▶ livekit TURN                 [중계, UDP 차단망]
backend ──https──▶ openvidu.i-eum.site (토큰 서명은 로컬, 방 생성·메타데이터·삭제만 호출)
```

| 항목 | 값 |
| --- | --- |
| 설치 경로 | `/opt/openvidu` (설정: `/opt/openvidu/config/`) |
| 서비스 | `sudo systemctl {status,restart} openvidu` — 컨테이너 `openvidu`, `caddy`, `ingress`, `egress`, `operator`, `dashboard`, `openvidu-meet`, `redis`, `mongo`, `minio`, 관측 스택 |
| 설치 옵션 | `--domain-name=openvidu.i-eum.site --turn-domain-name=turn.i-eum.site --external-proxy` (TLS는 호스트 nginx가 종료) |
| 신호 주소 | `wss://openvidu.i-eum.site` (클라이언트), `https://openvidu.i-eum.site` (backend) |
| 공인 IP | `3.34.139.114` (= `openvidu.i-eum.site`, `turn.i-eum.site`) |

호스트 nginx의 `openvidu.i-eum.site` 서버 블록(80→443, Let's Encrypt)이 `127.0.0.1:7880`으로 프록시한다. 원본은 repo `deploy/nginx/openvidu.conf`(서버 `/etc/nginx/sites-available/openvidu.conf`).

## 2. 포트

| 포트 | 프로토콜 | 대기 프로세스 | 용도 | 외부 개방 |
| --- | --- | --- | --- | --- |
| 443 | TCP | nginx | HTTPS·wss 신호 (서비스와 공유) | 기존 |
| 7880 | TCP | caddy | nginx → OpenVidu 내부 HTTP | 열지 않음 (127.0.0.1) |
| 7881 | TCP | livekit-server | WebRTC TCP (UDP가 막힌 네트워크의 대체 경로) | 필요 |
| 50000-60000 | UDP | livekit-server | WebRTC 음성 기본 경로 | 필요 |
| 443 | UDP | livekit TURN | TURN (`turn.i-eum.site`) | 필요 |
| 5349 | TCP | livekit-server | TURN TLS | 필요 |

EC2 안의 방화벽(ufw)에 다음을 추가했다 (2026-09-26). AWS 보안 그룹은 팀 권한 밖이며, 이 규칙들로 외부 접속이 확인됐다.

```bash
sudo ufw allow 7881/tcp && sudo ufw allow 5349/tcp && sudo ufw allow 443/udp && sudo ufw allow 50000:60000/udp
```

`22/tcp`(SSH) 규칙은 절대 지우지 않는다.

## 3. 공인 IP 고정 (`livekit.yaml`)

EC2는 NAT 뒤에 있어 livekit-server가 스스로 공인 IP를 찾지 못했다. 그러면 브라우저에 내부 IP(`172.26.3.7`, docker 브리지)만 후보로 알려
신호는 붙지만 음성 연결이 실패한다. 공인 IP를 직접 지정했다 (2026-09-26, 원본 백업: `livekit.yaml.bak`).

```yaml
# /opt/openvidu/config/livekit.yaml — rtc 블록
rtc:
    use_external_ip: false
    node_ip: 3.34.139.114
```

적용 후 `sudo systemctl restart openvidu`, 확인:

```bash
docker logs openvidu --since 1m 2>&1 | grep "starting LiveKit server"   # "nodeIP": "3.34.139.114" 이어야 한다
```

공인 IP가 바뀌면(인스턴스 교체 등) 이 값도 바꿔야 한다.

## 4. backend 연결 설정

| 환경변수 | 값 | 비고 |
| --- | --- | --- |
| `LIVEKIT_URL` | `https://openvidu.i-eum.site` | 클라이언트에는 backend가 `wss://`로 바꿔 내려준다 |
| `LIVEKIT_API_KEY` | `/opt/openvidu/config/openvidu.env`의 같은 이름 값 | 비밀값 |
| `LIVEKIT_API_SECRET` | 위와 같음 | 비밀값 |

셋 중 하나라도 없으면 backend가 기동하지 않는다(`LiveKitProperties`). 로컬은 `.env`, 운영은 Jenkins credential `itda-env-config`에 넣는다.

## 5. 동작 확인

1. 신호: `curl -s "https://openvidu.i-eum.site/rtc/validate?access_token=<토큰>"` → `success`
2. 포트: 로컬 PC에서 TCP 7881·5349 접속이 열려야 한다 (UDP는 도구로 확인이 어렵다 — 3번으로 확인)
3. 음성: backend로 강사·학습자 토큰을 받아 https://meet.livekit.io 의 Custom 탭 두 개에 `wss://openvidu.i-eum.site`와 함께 넣고,
   강사 탭에서 말하면 학습자 탭에서 들려야 한다. 학습자 탭의 `insufficient permissions to publish`는 정상이다(수신 전용 토큰)

## 6. 트러블슈팅

| 증상 | 원인 | 조치 |
| --- | --- | --- |
| 방 입장 전에 `Disconnected`, 참가자 이름도 안 뜸 | 신호 실패 (nginx 블록·인증서·토큰 만료) | 5절 1번, 토큰 `exp` 확인 (10분) |
| 이름은 뜨는데 `Disconnected`, 콘솔 `could not establish pc connection` | 음성 경로 실패 | 2절 포트 확인 → 서버 로그의 `ICE candidate pair stats`에서 local 후보가 내부 IP뿐이면 3절 |
| 로그 `Address rewrite mapping is enabled but no external IP is found` | 공인 IP 자동 감지 실패 | 3절 |
| 로그 `failed to send webhook ... 127.0.0.1:9080` | openvidu-meet 웹훅 대상이 떠 있지 않음 | 음성과 무관, 무시 가능 |
| 참가자가 모두 나가면 `room closed` | LiveKit 빈 방 자동 정리 | 정상. 강사가 토큰을 다시 받으면 backend가 방을 다시 연다 |

## 7. 남은 과제

- ufw 규칙과 `livekit.yaml` 변경은 서버 재구축 시 수동 재적용이 필요하다.

## 8. OpenVidu Meet·MinIO (사람이 실행, 수업 없는 시간에)

2026-09-27 점검에서 `openvidu-meet`(재시작 1,507회)과 `minio`(1,320회)가 S3 연결 실패로 재시작을 반복했다. 우리 음성은 LiveKit API를 직접 쓰므로 Meet이 필요 없다.

### 8.1 Meet 끄기

OpenVidu 3.8 단일 노드는 `/opt/openvidu/config/openvidu.env`의 `ENABLED_MODULES`로 선택 모듈(Community: `openviduMeet`, `observability`)을 켠다. `openviduMeet`만 빼고 재시작한다. **재시작 동안 진행 중인 수업 음성이 끊긴다.**

```bash
sudo cp /opt/openvidu/config/openvidu.env /opt/openvidu/config/openvidu.env.bak-$(date +%Y%m%d)
grep -n '^ENABLED_MODULES' /opt/openvidu/config/openvidu.env        # 예: ENABLED_MODULES=observability,openviduMeet
sudo sed -i -E '/^ENABLED_MODULES=/{s/(^|,)openviduMeet(,|$)/\1\2/; s/,,/,/; s/=,/=/; s/,$//}' /opt/openvidu/config/openvidu.env
grep -n '^ENABLED_MODULES' /opt/openvidu/config/openvidu.env        # openviduMeet이 빠졌는지 (observability는 남긴다)
sudo systemctl restart openvidu
```

확인:

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'openvidu-meet|minio|openvidu$'   # openvidu-meet이 없어야 한다
docker logs openvidu --since 2m 2>&1 | grep "starting LiveKit server"                    # "nodeIP": "3.34.139.114" (3절 설정 유지)
```

이어서 5절 동작 확인(신호·음성)을 한다. 되돌리기: 백업한 `openvidu.env.bak-<날짜>`를 되돌려 놓고 `sudo systemctl restart openvidu`.

### 8.2 MinIO

MinIO는 선택 모듈이 아니라 기본 구성(녹화 등의 S3 저장소)이라 끄는 공식 설정이 없다. Meet을 끈 뒤에도 재시작이 이어지는지 먼저 본다:

```bash
docker inspect minio --format '{{.RestartCount}} {{.State.Status}}'
docker logs --tail 50 minio
```

재시작이 계속되면 로그의 원인(데이터 폴더 권한·자격 증명 등)을 기록해 팀과 조치를 정한다. 녹화·egress를 쓰지 않으므로 음성에는 영향이 없다.
