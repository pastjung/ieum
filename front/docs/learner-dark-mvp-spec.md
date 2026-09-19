# 학습자 앱 · Dark 테마 MVP 핵심 플로우 명세 (1차)

기준 문서: FRONTEND_MONOREPO_STRUCTURE.md · FRONTEND_LIBRARY_GUIDE.md · 시각장애인용 앱 Dark 와이어프레임(37화면 중 MVP 핵심 플로우)

## 1. 범위

이번 차수(feat/front-learner-dark-ui)는 **UI + 목데이터 + TTS/햅틱**까지 구현한다.
서버 API·BLE·실시간 수업(LIVE)·예외 복구 화면은 다음 차수. 서버/BLE는 목(mock) 서비스 계층 뒤에 숨겨 교체 가능하게 한다.

구현 화면 (와이어프레임 번호):

| 라우트 | 화면 | 와이어프레임 |
| --- | --- | --- |
| `/` | 스플래시 → 분기 | V00 |
| `/onboarding/mode` | 사용 방식 선택 (음성/화면 중심, 최초 1회) | V00a |
| `/auth/sign-up` | 회원가입 3단계 (아이디→비밀번호→이메일) | V01 |
| `/auth/verify-email` | 이메일 인증 안내 | V02 |
| `/auth/sign-in` | 로그인 | V03 |
| `/auth/welcome` | 로그인 완료 | V04 |
| `/onboarding/accessibility` | 소리와 진동 설정 | V05 |
| `/onboarding/guide` | 사용 안내 3단계 (6점 패드 체험) | V09 |
| `/(tabs)/` | 홈 · 이어하기/복습 제안 변형 | V10·V11 |
| `/(tabs)/learn` | 테마 선택 (자음·모음·약자·단어·약어·문장) | V12 |
| `/(tabs)/class` | 수업 탭 자리표시 (다음 차수) | — |
| `/(tabs)/settings` | 설정 (테마 Dark/Green 토글 포함) | V27 |
| `/session/intro` | 세션 안내 | V13 |
| `/session/play` | 문제 풀이 · 6점 V패드 | V14 |
| `/session/grade` | 채점 정답/오답+차이 | V15·V16 |
| `/session/analyzing` | 분석 중 | V17 |
| `/session/summary` | 학습 결과 | V18 |

## 2. 폴더 구조 (모노레포 문서 준수)

```
front/
├─ apps/mobile/            # Expo + expo-router 학습자 앱 (@ieum/mobile)
│  └─ src/
│     ├─ app/              # expo-router 라우트
│     ├─ components/       # DarkButton, DotPad, BrailleCellView, ScreenShell ...
│     ├─ features/         # auth, learning, settings 별 화면 로직
│     ├─ services/         # mock: authService, learningService (교체 지점)
│     ├─ stores/           # zustand: settingsStore(음성·진동·테마·모드)
│     └─ a11y/             # speak(), haptic(), announce() 공용 유틸
└─ packages/
   ├─ design-tokens/       # @ieum/design-tokens — dark(우선)·green 테마 토큰
   ├─ domain/              # @ieum/domain — 점자 셀(6비트) 변환·채점·차이 설명
   └─ contracts/           # @ieum/contracts — zod 스키마(로그인·답안·세션)
```

## 3. 디자인 토큰 (Dark, 와이어프레임 추출)

- bg `#0B0B0C` · surface `#1C1C1E` · border `#48484C`
- text `#FFFFFF` · textMuted `#B9B9BD` · primary `#FFD400` · onPrimary `#111111`
- radius: card 16 / control 14 / screen 24 — 버튼 최소 높이: primary 72 / secondary 60 / compact 56
- 점 버튼 92px 이상, 본문 대비 7:1 이상, 화면당 핵심 행동 1개

## 4. 도메인 규칙

- 점자 셀은 전 구간 6비트 정수(dot1=bit0 … dot6=bit5, U+2800 오프셋) — AGENTS.md 규칙 준수, `@ieum/domain` 단일 구현
- 채점: 셀 배열 비교 → 첫 불일치 셀 index + 누락/추가 점 목록 → V16 문구("3번 점이 빠졌어요 · 다섯 번째 칸에서요") 생성
- 입력 제스처(1차 단순화): 점 버튼 다중 터치 후 모두 떼면 한 칸 입력, 삭제 버튼(두 손가락 쓸기는 다음 차수), 제출은 길게 누르기

## 5. 접근성 (docs/conventions/code/mobile.md 준수)

- 모든 조작 요소에 accessibilityRole/Label(+Hint), 상태 변화는 announceForAccessibility
- TTS(expo-speech)·햅틱(expo-haptics)은 src/a11y/ 유틸 경유만 허용 — 속도·중복 방지 정책 일원화
- 정답/오답: 음성 + 햅틱(성공: notificationAsync Success, 오답: Error) + 화면 텍스트 동시 전달, 색만으로 전달 금지

## 6. 상태·데이터

- 서버 데이터: TanStack Query + mock service (fetch 대신 지연 Promise) — 이후 @ieum/api-client 로 교체
- 설정(음성 켬/빠르기/진동/테마/사용방식): zustand + AsyncStorage persist
- 토큰: expo-secure-store (mock 로그인 성공 시 저장)
- 폼: react-hook-form + zod(@ieum/contracts)

## 7. 완료 기준

- `pnpm --filter @ieum/mobile typecheck` 통과, jest-expo 단위테스트(도메인·채점) 통과
- Expo Go에서 스플래시→로그인→홈→학습 세션→결과→설정 흐름이 목데이터로 동작함을 확인
- TalkBack 시나리오 수동 확인은 실기기 필요 — 체크리스트로 MR에 명시
