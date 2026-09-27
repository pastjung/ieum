# 2. 외부 서비스 정보

프로젝트가 사용하는 외부 서비스와 가입·설정에 필요한 정보. 키·비밀번호 값은 여기 적지 않는다(보관 위치만).

| 서비스 | 용도 | 가입·발급 | 설정이 들어가는 곳 |
| --- | --- | --- | --- |
| **Cartesia** (음성 복제·TTS) | 학습자가 녹음한 목소리를 복제해 잇다(음성 비서) 안내를 그 목소리로 합성. 모델 `sonic-3.6`, `POST /voices/clone`(WAV 업로드) · `POST /tts/bytes`(MP3) | https://play.cartesia.ai 가입 → API Keys에서 발급 (Pro 플랜 사용). 헤더 `Authorization: Bearer`, `Cartesia-Version: 2026-08-14` | backend `CARTESIA_API_KEY` (선택 — 없으면 목소리 API만 503). 앱 릴리스 번들에는 넣지 않는다 |
| **OpenVidu Community (LiveKit)** | 실시간 수업의 강사 → 학습자 단방향 음성 | 서버(EC2)에 `openvidu.i-eum.site` 도메인으로 설치: `sh <(curl -fsSL http://get.openvidu.io/community/singlenode/latest/install.sh) --install --domain-name=openvidu.i-eum.site --turn-domain-name=turn.i-eum.site --enabled-modules=observability,openviduMeet --external-proxy …` (키·비밀번호 인자는 설치 담당자가 보관). 설치 후 `/opt/openvidu/config/openvidu.env`에 `LIVEKIT_API_KEY`·`LIVEKIT_API_SECRET` | backend `LIVEKIT_URL=https://openvidu.i-eum.site`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`. 토큰은 backend가 발급하고 앱·웹은 `wss://openvidu.i-eum.site`로 접속 (`livekit-client` / `@livekit/react-native`) |
| **네이버 메일 SMTP** | 회원가입 이메일 인증, 비밀번호 재설정 메일 | 네이버 계정 → 메일 설정 → POP3/IMAP·SMTP 사용 + **애플리케이션 비밀번호** 발급. `smtp.naver.com` 465 (SSL) | backend `MAIL_SMTP_*`, `MAIL_FROM_ADDRESS`, `MAIL_LINK_*`, `MAIL_DISPATCH_ENABLED=true` |
| **GitLab (SSAFY)** | 소스·MR·Releases·패키지(APK 보관) | `https://lab.ssafy.com/s15-bigdata-recom-sub1/S15P21B201` 팀 계정. Webhook(Push `release/*`, Tag push) → Jenkins. 개인 액세스 토큰(scope `api`)은 APK 릴리스 스크립트와 Jenkins 커밋 상태 회신에 사용 | Jenkins credential `gitlab-api-token`(플러그인용)·`gitlab-api-token-text`(스크립트용), PC 환경변수 `GITLAB_TOKEN`(release-app.sh) |
| **Jenkins** (자체 호스팅, Docker) | MR 검증(`itda-mr-verify`), 배포(`itda-deploy`), 롤백(`itda-rollback`) | 서버 `deploy/jenkins/` compose로 기동. 최초 설정은 `docs/infra/jenkins.md` | Credentials: `gitlab-api-token`, `gitlab-api-token-text`, `gms-api-key`, `ec2-deploy-key`(SSH `ubuntu`), `itda-env-config`(운영 `.env.config` Secret file) |
| **GMS (SSAFY LLM 게이트웨이)** | MR의 AI 코드리뷰(`scripts/ai-review.py`) — 서비스 기능 아님 | `https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions`, SSAFY 발급 키 | Jenkins credential `gms-api-key` |
| **AWS EC2 (SSAFY 제공)** | 서버 호스트 `j15b201.p.ssafy.io` (Ubuntu) | SSAFY 제공 인스턴스 + pem 키 | pem은 저장소 밖. `ec2-deploy-key`(Jenkins), `IEUM_DEPLOY_KEY`(release-app.sh) |
| **도메인 `i-eum.site`** | 서비스 주소 (`i-eum.site`, `www`, `openvidu.`, `turn.`) | 도메인 등록 대행에서 구매, A 레코드 → EC2 공인 IP | `deploy/nginx/itda.conf`·`openvidu.conf` `server_name` |
| **Let's Encrypt (certbot)** | HTTPS 인증서 | 서버에서 `certbot --nginx -d i-eum.site -d www.i-eum.site` (openvidu는 설치 스크립트가 자체 발급). 90일 자동 갱신 | `/etc/letsencrypt/live/i-eum.site/fullchain.pem`, `privkey.pem` (nginx conf 참조) |
| **Google 음성 인식 (Android 내장)** | 잇다 호출어("하이 잇다")·명령 받아쓰기 — `expo-speech-recognition`이 기기의 `SpeechRecognizer`(Google 앱 `com.google.android.googlequicksearchbox`) 사용 | 별도 가입 없음. 폰에 Google 앱·한국어 음성 인식 설치 필요, 온라인 인식 | `app.json` plugin `expo-speech-recognition`의 `androidSpeechServicePackages` |
| **Android 시스템 TTS** | 화면 안내 낭독(`expo-speech`), 복제 목소리 미설정 시 폴백 | 폰 설정 → 텍스트 음성 변환에 한국어 음성 설치 | 없음 |
| **Mattermost** (SSAFY) | 배포·롤백 결과 알림 | 채널 Incoming Webhook | Jenkins 파이프라인(`Jenkinsfile`) 환경변수 |
| **Tailscale** (개발 편의) | 팀 공용 개발 DB(MariaDB)·Redis에 팀원 PC가 사설망으로 접근 | 팀 tailnet 초대 → 클라이언트 설치 | 개발자 PC의 `.env`(`DB_URL`/`DB_HOST`) — 운영과 무관 |

## 하드웨어 (외부 서비스는 아니지만 시연에 필요)

| 항목 | 내용 |
| --- | --- |
| Arduino Uno 계열 + HC-06(Bluetooth Classic) + TB6612 모터 드라이버 + 솔레노이드 6개 | 1셀 6점 촉각 모듈. 펌웨어 `iot/braille_solenoid/braille_solenoid.ino`, 프로토콜·배선 `iot/braille_solenoid/README.md` |
| HC-06 페어링 | 폰 블루투스 설정에서 `HC-06` 페어링, PIN **0713** (모듈에 `AT+PIN0713`으로 설정해 둠). 앱은 페어링된 기기 중 이름에 `HC-06`이 든 것을 고른다 |

## 가입 시 참고

- Cartesia: 무료 플랜은 복제 횟수·문자 수 제한이 있어 Pro 플랜에서 시험했다. 키는 교체 예정이며 앱 번들에 절대 넣지 않는다(릴리스 APK에 `sk_car` 문자열이 없어야 한다 — `release-app.sh`가 확인).
- 네이버 SMTP는 하루 발송 한도가 있다. 시연용 계정은 인증 메일 수십 통 수준이면 충분.
- OpenVidu 설치 스크립트 인자(관리자 비밀번호, Redis·Mongo·MinIO 비밀번호)는 서버 `/opt/openvidu/config/`에만 있다. 재설치 시 같은 값을 써야 기존 데이터와 맞는다.
