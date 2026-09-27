# exec — 포팅 매뉴얼 (이음 I:EUM)

기준 브랜치: `release/0.1.0` · 작성일: 2026-09-28

| # | 문서 | 내용 |
| --- | --- | --- |
| 1 | [01-build-and-deploy.md](./01-build-and-deploy.md) | 클론 후 빌드·배포. 사용 스택과 버전(IDE 포함), 빌드 환경변수 상세, 배포 특이사항, 계정·프로퍼티 파일 목록 |
| 2 | [02-external-services.md](./02-external-services.md) | 외부 서비스(Cartesia, OpenVidu/LiveKit, SMTP, GitLab·Jenkins, EC2·도메인·인증서 등) 가입·설정 정보 |
| 3 | [03-db-dump/](./03-db-dump/README.md) | DB 덤프 파일과 복원 방법 |
| 4 | [04-demo-scenario.md](./04-demo-scenario.md) | 시연 시나리오 — 강사 웹·학습자 앱 화면별 클릭 순서 |
| — | [env/](./env/) | 환경변수 템플릿(키·형식·설명). 실제 값은 저장소에 두지 않는다(아래 참고) |

## 한눈에 보는 구성

```
학습자 앱 (Android, Expo/RN)  ──HTTPS──▶  nginx (i-eum.site)  ──▶  backend (Spring Boot :8080)  ──▶  MariaDB / Redis
강사 웹  (React/Vite, nginx)  ──HTTPS──▶       │                       └──HTTP──▶ ai (FastAPI :8000)
                                               └─(openvidu.i-eum.site)──▶ OpenVidu/LiveKit (실시간 음성)
학습자 앱 ──Bluetooth Classic(SPP)──▶ Arduino 6점 촉각 모듈 (iot/braille_solenoid)
```

## 환경 파일에 대하여

저장소 정책상(AGENTS.md, `scripts/check-no-env-files.sh` 커밋 훅) `.env` 계열 파일은 커밋되지 않는다. 이 폴더의 `env/*.example`은 **필요한 키 전부와 형식·설명**을 담고 있으며, 실제 값은 배포 담당자가 별도 채널(SSAFY 제출용 압축본, Jenkins Secret file `itda-env-config`)로 보관한다. 값을 채운 파일을 이 폴더에 두려면 파일명이 `.env`로 시작하지 않게 하고, 저장소가 아니라 제출본에만 포함한다.
