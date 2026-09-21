# 아키텍처

두 장으로 보는 전체 시스템. **사용자 아키텍처**는 "사용자의 요청이 어떻게 처리되는가", **시스템 아키텍처**는 "우리 코드가 어떻게 서버까지 가는가"를 그린다. (GitLab이 아래 Mermaid 블록을 다이어그램으로 렌더링한다)

## 1. 사용자 아키텍처 — 요청 하나의 여정

```mermaid
flowchart LR
    U["👤 사용자<br/>(브라우저 · 모바일 앱)"] -- "https://j15b201.p.ssafy.io<br/>(HTTPS, 443만 개방)" --> NX

    subgraph EC2["EC2 서버 (Ubuntu · 방화벽 3중 방어)"]
        NX["🚪 호스트 Nginx<br/>HTTPS 해제 + 경로 교통정리<br/>+ blue/green 전환 스위치"]

        subgraph WEB["web (React 정적 화면)"]
            WB["🟦 blue :3001"]
            WG["🟩 green :3002"]
        end
        subgraph BE["backend (Spring Boot API)"]
            BB["🟦 blue :8081"]
            BG["🟩 green :8082"]
        end
        subgraph AI["ai (FastAPI — 외부에서 접근 불가)"]
            AB["🟦 blue :8101"]
            AG["🟩 green :8102"]
        end

        NX -- "/ → 화면" --> WB
        NX -. "대기 슬롯 (직전 버전)" .-> WG
        NX -- "/api/ → API" --> BB
        NX -. "대기 슬롯" .-> BG
        BB -- "AI 기능 내부 호출<br/>(Nginx 내부 리스너 :9100 경유)" --> AB
    end

    BB -. "RDS 연결 예정<br/>(현재는 H2 임시 — 재배포 시 데이터 소실)" .-> RDS[("🗄️ RDS MySQL<br/>(예정)")]
```

- **실선 = 지금 트래픽이 흐르는 길**, 점선 = 대기 슬롯/예정. 서비스마다 슬롯이 2개씩 있고 Nginx가 한쪽으로만 트래픽을 보낸다 — 배포는 빈 슬롯에 새 버전을 띄우고 스위치만 돌리는 방식이라 무중단이다 ([deploy.md](./deploy.md) 7절).
- 외부에서 접근 가능한 문은 사실상 호스트 Nginx(443) 하나다. 컨테이너 포트는 전부 127.0.0.1 바인딩이고, ai는 backend만 내부 리스너(9100)로 호출한다 ([deploy.md](./deploy.md) 3절).

## 2. 시스템 아키텍처 — 코드가 서버에 도착하기까지 (CI/CD)

```mermaid
flowchart TB
    DEV["👩‍💻 개발자"] -- "① MR 생성" --> GL["🦊 GitLab<br/>(lab.ssafy.com)"]

    GL -- "Webhook: MR 사건" --> VERIFY
    GL -- "Webhook: release 병합 · v태그" --> DEPLOY

    subgraph JENKINS["Jenkins (EC2 안 컨테이너, 비밀값은 Credentials 금고에)"]
        VERIFY["🔍 itda-mr-verify<br/>변경된 서비스만 테스트"]
        DEPLOY["🚀 itda-deploy<br/>Plan→Sync→Build→Deploy→Cleanup"]
        ROLLBACK["↩️ itda-rollback<br/>(사람이 수동 실행)"]
    end

    VERIFY -- "② 결과 ✅/❌ 를 MR에 표시" --> GL
    VERIFY -- "diff 리뷰 요청" --> GMS["🤖 GMS (LLM)"]
    GMS -- "AI 리뷰 코멘트" --> GL

    DEPLOY -- "③ 이미지 빌드·보관<br/>(v*/rc 각 3개 = 롤백 지점)" --> IMG[("📦 이미지 저장소<br/>(서버 로컬)")]
    DEPLOY -- "④ SSH → deploy.sh<br/>(사람 수동 배포와 같은 스크립트)" --> BGDEPLOY["🔄 blue-green 무중단 배포<br/>헬스체크 통과 시에만 전환"]
    ROLLBACK -- "같은 deploy.sh로<br/>보관된 태그 재배포" --> BGDEPLOY

    DEPLOY -- "⑤ 배포 결과 + AI 변경 요약" --> MM["💬 Mattermost<br/>B201 채널 알림"]
    ROLLBACK -- "롤백 알림" --> MM
```

- **dev/main 병합은 배포로 이어지지 않는다** — 서버로 가는 길은 release 병합과 v* 태그뿐 ([pipeline.md](./pipeline.md) 5절).
- 사람이 하는 일은 ①(MR)과 릴리스 담당의 클릭(release 생성·태그·롤백)뿐, ②~⑤는 자동.
- 배포와 롤백이 **같은 deploy.sh**를 쓰는 것이 핵심 설계 — 자동/수동/롤백이 다르게 동작하는 사고가 구조적으로 불가능하다.
