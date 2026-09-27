# 실시간 수업 양방향 음성 — 학습자 앱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학습자 앱 풀이 화면에서 손들기·내리기와, 강사가 발언을 허용한 동안에만 쓸 수 있는 마이크 켜기·끄기를 제공하고, 허용·회수를 음성으로 알리며, 수업 마이크를 쓰는 동안 "하이 잇다"가 마이크를 비켜 준다.

**Architecture:** LiveKit 방은 지금처럼 `liveLecture.ts` 모듈이 소유한다. 로컬 참가자의 권한 변화(`RoomEvent.ParticipantPermissionsChanged`)와 속성 변화(`RoomEvent.ParticipantAttributesChanged`)를 해석하는 판단은 순수 함수(`lectureSignals.ts`)로 떼고, 모듈은 (1) 새 핸들러 `onSpeakingChanged(allowed)`로 **사건**을 알리고(대기 화면이 등록 — 화면을 옮겨도 살아 있다, 낭독 담당) (2) 새 zustand 스토어 `lectureVoiceStore`에 **상태**(`speakingAllowed`·`micOn`·`handUp`)를 써서 풀이 화면이 구독해 버튼을 그리게 한다(기존 `itda.ts`→`itdaStore`, `tactileDevice`→`tactileStore`와 같은 구조). 잇다와의 마이크 공유는 `itda.ts`에 "수업 때문에 쉬기" 플래그(`pauseItdaForLecture`)를 두어, 앱 복귀(`_layout`의 AppState)로 `startItda`가 불려도 수업 마이크가 켜진 동안에는 시작하지 않게 한다.

**Tech Stack:** Expo 57, React Native 0.86.3, expo-router, zustand 5, `livekit-client@2.22.3`, `@livekit/react-native@3.0.0`, `expo-audio@~57.0.5`(마이크 권한), jest-expo

**Spec:** `docs/superpowers/specs/2026-09-26-lecture-two-way-voice-design.md` §2, §3.3, §5, §8 / 교차 계약 `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/voice-contract.md` (읽기 전용 — 이 파일은 git에 넣지 않는다)

## Global Constraints

- 브랜치: `feat/lecture-voice-app` (`feat/lecture-voice`에서 분기), 작업 폴더 `C:/Users/SSAFY/Desktop/wt-voice-app`, MR 대상 `feat/lecture-voice`. **push하지 않는다** (MR은 사람이 올린다)
- 작업 위치: `front/apps/mobile` 만. backend·web·contracts는 건드리지 않는다. 요청과 무관한 파일을 고치지 않는다
- 커밋: `type(lecture): 한글 제목` + 빈 줄 + 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `git add`는 **경로를 하나하나 지정**한다(`git add .`/`-A` 금지) — 추적되지 않는 `.superpowers/`를 절대 넣지 않는다. `--no-verify`, `push --force` 금지
- `.env*` 파일은 읽지도 고치지도 않는다. 이 작업은 환경변수를 추가하지 않는다
- 프로세스를 끌 때는 PID로만 끈다(`taskkill /PID <pid> /F`). 이름으로 일괄 종료(`taskkill /IM node.exe` 등) 금지
- 검증: `cd front && pnpm --filter @ieum/mobile typecheck` 와 `pnpm --filter @ieum/mobile exec jest` 전체 통과. 실패 상태로 완료를 선언하지 않는다
- 계약(verbatim):
  - 학습자 토큰: `hidden` 제거, `canUpdateOwnMetadata=true` 추가, `canPublish=false`, `canPublishData=false`, `canSubscribe=true`
  - identity `user-{userId}`, 속성(string map): `hand`: `"up"` | `"down"` — 학습자가 `localParticipant.setAttributes({hand})`로, 백엔드가 허용·회수 시 `"down"`으로 바꾼다. `speaking`: `"allowed"` | `"none"` — 백엔드만 쓴다, **표시 전용**
  - 실제 송출 가능 여부 = `participant.permissions?.canPublish` (그리고 `canPublishSources`가 MICROPHONE을 포함). 회수되면 LiveKit이 학습자의 게시 트랙을 강제로 내린다
  - 이벤트: `RoomEvent.ParticipantPermissionsChanged (prevPermissions, participant)` — 로컬 참가자에게도 온다. `RoomEvent.ParticipantAttributesChanged (changed, participant)`
- 설치본에서 확인한 API (livekit-client 2.22.3 `dist/src/room/...d.ts`, @livekit/react-native 3.0.0 `src/audio/AudioSession.ts`):
  - `LocalParticipant.setAttributes(attributes: Record<string, string>): Promise<void>`
  - `LocalParticipant.setMicrophoneEnabled(enabled: boolean, ...): Promise<LocalTrackPublication | undefined>`
  - `Participant.permissions?: ParticipantPermission` (`canPublish: boolean`, `canPublishSources: TrackSource[]`, protocol `TrackSource.MICROPHONE = 2`, **빈 배열이면 모든 소스 허용** — `LocalParticipant.hasPermissionsToPublish`와 같은 규칙)
  - `Participant.attributes: Readonly<Record<string, string>>`
  - `RoomEventCallbacks.participantPermissionsChanged: (prevPermissions: ParticipantPermission | undefined, participant: RemoteParticipant | LocalParticipant) => void` — 로컬은 `Room.onLocalParticipantPermissionsChanged`가 `this.localParticipant`를 넘겨 낸다
  - `RoomEventCallbacks.participantAttributesChanged: (changedAttributes: Record<string, string>, participant: RemoteParticipant | LocalParticipant) => void`
  - 서버가 트랙을 내리면 클라이언트는 `handleLocalTrackUnpublished` → `unpublishTrack(track)`(기본 stopOnUnpublish)로 마이크 트랙을 멈춘다
  - `AudioSession.configureAudio({ android: { audioTypeOptions } })` — 주석은 "방 접속 전에 불러야 올바르게 적용"이라 한다. 네이티브 구현은 호출 때마다 `audioManager.setAudioMode`·`setAudioOutputAttributes(adm, …)`를 즉시 반영하지만, 이미 재생 중인 트랙에 적용되는지는 **실기기로 판정**한다(Task 7). 설계서 §5가 "발언 허용 여부와 관계없이 통신 프로필"을 허용하므로 대안 코드를 Task 7에 둔다
  - `AndroidAudioTypePresets.communication`(`audioMode: 'inCommunication'`, usage `voiceCommunication`), `AndroidAudioTypePresets.media`(`audioMode: 'normal'`, usage `media`)
  - 마이크 권한: 앱이 이미 쓰는 `AudioModule.requestRecordingPermissionsAsync()` (`expo-audio`, `src/services/voiceSample.ts`와 같은 방식). `app.json`에 `android.permission.RECORD_AUDIO`가 이미 있다
- 네이티브 의존성·`app.json`을 바꾸지 않는다 → 개발 빌드 재생성(prebuild) 불필요
- 접근성(시각장애 학습자, `docs/conventions/code/mobile.md`): 모든 버튼에 라벨·역할(`AppButton`이 부여)·결과가 자명하지 않으면 힌트, 상태 변화는 `@/a11y`의 `announce`/`speakAsync`/`hapticSuccess`로만 알린다(컴포넌트에서 `expo-speech` 직접 호출 금지). 색만으로 상태를 전하지 않는다. 마이크가 켜진 동안의 TTS는 수업에 그대로 전해지므로 **켜기 안내는 켜기 전에** 말하고 켜진 것은 진동으로 알린다
- 문구(정확히 이대로):
  - 허용: `선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요.`
  - 회수: `선생님이 발언을 마쳤어요.`
  - 마이크 비활성 힌트: `선생님이 허용하면 말할 수 있어요`
  - 권한 거부: `설정에서 마이크 권한을 허용해 주세요`

## File Structure

| 파일 | 책임 |
| --- | --- |
| `front/apps/mobile/src/services/lectureSignals.ts` (수정) | `canSpeak(permissions)`, `handFromAttributes(attributes)` 순수 함수 추가 |
| `front/apps/mobile/src/services/itda.ts` (수정) | `pauseItdaForLecture(paused)` — 수업 마이크가 켜진 동안 잇다를 멈추고 `startItda`를 막는다 |
| `front/apps/mobile/src/stores/lectureVoiceStore.ts` (신규) | 내 발언 상태(`speakingAllowed`·`micOn`·`handUp`) — 화면 표시용 |
| `front/apps/mobile/src/services/liveLecture.ts` (수정) | 로컬 권한·속성 이벤트, `onSpeakingChanged`, 오디오 프로필 전환, `raiseHand`, `setMicrophone`, 퇴장 시 마이크·잇다 정리 |
| `front/apps/mobile/src/services/micPermission.ts` (신규) | `ensureMicPermission()` — RECORD_AUDIO 런타임 요청 |
| `front/apps/mobile/src/services/lectureVoiceControls.ts` (신규) | 버튼 라벨·힌트·상태 문구와 안내 문구 (순수) |
| `front/apps/mobile/src/app/class/waiting.tsx` (수정) | `onSpeakingChanged` 등록 — 허용·회수 낭독 |
| `front/apps/mobile/src/app/class/play.tsx` (수정) | 손들기·마이크 버튼, 상태 라벨 |
| `front/apps/mobile/__tests__/lectureSignals.test.ts`, `itdaLecturePause.test.ts`(신규), `liveLecture.test.ts`, `micPermission.test.ts`(신규), `lectureVoiceControls.test.ts`(신규) | 테스트 |

---

### Task 0: 작업 폴더 준비

**Files:** 없음

- [ ] **Step 1: 워크트리와 브랜치 만들기**

```bash
cd C:/Users/SSAFY/Desktop/wt-lecture-voice
git fetch origin
git status --short          # 상위 워크트리 상태 확인만 한다 (여기서 커밋하지 않는다)
git worktree add ../wt-voice-app -b feat/lecture-voice-app feat/lecture-voice
cd ../wt-voice-app
git log --oneline -1        # feat/lecture-voice의 최신 커밋과 같아야 한다
```

이 계획 문서가 `feat/lecture-voice`에 아직 커밋돼 있지 않으면 원본 경로(`C:/Users/SSAFY/Desktop/wt-lecture-voice/docs/superpowers/plans/2026-09-26-lecture-voice-app.md`)를 읽기만 하고, 이 브랜치에 복사·커밋하지 않는다(계획 문서 커밋은 상위 브랜치 몫).

- [ ] **Step 2: 의존성 설치와 기준선 확인**

```bash
cd C:/Users/SSAFY/Desktop/wt-voice-app/front
pnpm install
pnpm --filter @ieum/mobile typecheck
pnpm --filter @ieum/mobile exec jest
```

Expected: 타입 오류 없음, 전체 PASS (기준선). 실패하면 이 계획의 작업을 시작하기 전에 원인을 보고한다.

---

### Task 1: 발언 권한·손 상태 해석 (순수 함수)

**Files:**
- Modify: `front/apps/mobile/src/services/lectureSignals.ts` (파일 끝에 추가)
- Test: `front/apps/mobile/__tests__/lectureSignals.test.ts` (파일 끝에 추가, 첫 줄 import 수정)

**Interfaces:**
- Produces:
  - `MICROPHONE_SOURCE = 2` (livekit protocol `TrackSource.MICROPHONE`)
  - `interface PublishPermissions { canPublish: boolean; canPublishSources: readonly number[] }`
  - `canSpeak(permissions: PublishPermissions | undefined): boolean`
  - `type Hand = "up" | "down"`
  - `handFromAttributes(attributes: Readonly<Record<string, string>> | undefined): Hand`

- [ ] **Step 1: 실패하는 테스트 작성**

`__tests__/lectureSignals.test.ts` 첫 줄 import를 바꾼다:

```ts
import {
  canSpeak,
  classifyDisconnect,
  handFromAttributes,
  isLectureOpen,
  isUserBackAction,
  MICROPHONE_SOURCE,
  ROOM_DELETED_REASON,
} from "@/services/lectureSignals";
```

파일 끝에 추가:

```ts
describe("canSpeak", () => {
  it("게시 권한과 마이크 소스가 있으면 말할 수 있다", () => {
    expect(canSpeak({ canPublish: true, canPublishSources: [MICROPHONE_SOURCE] })).toBe(true);
  });

  it("소스 목록이 비어 있으면 모든 소스가 허용된 것이다 (LiveKit 규칙)", () => {
    expect(canSpeak({ canPublish: true, canPublishSources: [] })).toBe(true);
  });

  it("게시 권한이 없으면 말할 수 없다", () => {
    expect(canSpeak({ canPublish: false, canPublishSources: [MICROPHONE_SOURCE] })).toBe(false);
    expect(canSpeak({ canPublish: false, canPublishSources: [] })).toBe(false);
  });

  it("마이크가 아닌 소스만 허용되면 말할 수 없다", () => {
    expect(canSpeak({ canPublish: true, canPublishSources: [1] })).toBe(false);
  });

  it("권한 정보가 아직 없으면 말할 수 없다", () => {
    expect(canSpeak(undefined)).toBe(false);
  });
});

describe("handFromAttributes", () => {
  it("hand가 up이면 손을 든 것이다", () => {
    expect(handFromAttributes({ hand: "up" })).toBe("up");
  });

  it("down·다른 값·없음은 모두 내린 것이다", () => {
    expect(handFromAttributes({ hand: "down" })).toBe("down");
    expect(handFromAttributes({ hand: "UP" })).toBe("down");
    expect(handFromAttributes({ speaking: "allowed" })).toBe("down");
    expect(handFromAttributes({})).toBe("down");
    expect(handFromAttributes(undefined)).toBe("down");
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureSignals.test.ts`
Expected: FAIL — `canSpeak is not a function` 등 (export 없음, 타입 오류로 실패해도 된다)

- [ ] **Step 3: 구현**

`src/services/lectureSignals.ts` 파일 끝에 추가:

```ts
/** livekit protocol TrackSource.MICROPHONE */
export const MICROPHONE_SOURCE = 2;

/** LiveKit ParticipantPermission 중 송출 판단에 쓰는 부분 (네이티브 없이 테스트하려고 타입을 직접 둔다) */
export interface PublishPermissions {
  canPublish: boolean;
  canPublishSources: readonly number[];
}

/**
 * 실제로 마이크를 송출할 수 있는지 — 선생님의 발언 허용은 이 권한으로만 판단한다.
 * `speaking` 속성은 표시용이라 보지 않는다. 소스 목록이 비어 있으면 LiveKit은 모든 소스를 허용한다.
 */
export function canSpeak(permissions: PublishPermissions | undefined): boolean {
  if (!permissions?.canPublish) return false;
  return permissions.canPublishSources.length === 0 || permissions.canPublishSources.includes(MICROPHONE_SOURCE);
}

export type Hand = "up" | "down";

/** 참가자 속성의 손 상태. 정확히 "up"일 때만 든 것으로 본다 (백엔드는 허용·회수 때 "down"으로 바꾼다) */
export function handFromAttributes(attributes: Readonly<Record<string, string>> | undefined): Hand {
  return attributes?.hand === "up" ? "up" : "down";
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureSignals.test.ts`
Expected: PASS (기존 테스트 포함)

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/src/services/lectureSignals.ts front/apps/mobile/__tests__/lectureSignals.test.ts
git commit -m "feat(lecture): 발언 권한과 손 상태 해석 함수를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 수업 마이크가 켜진 동안 잇다를 쉬게 한다

**Files:**
- Modify: `front/apps/mobile/src/services/itda.ts` (`startItda` 앞에 플래그, `startItda` 첫 줄, 파일 끝에 함수)
- Test: `front/apps/mobile/__tests__/itdaLecturePause.test.ts` (신규)

**Interfaces:**
- Produces: `pauseItdaForLecture(paused: boolean): void` — `true`: 잇다를 멈추고(`stopItda`) 이후 `startItda()`를 무시한다. `false`: 막음을 풀고 `startItda()`를 부른다(설정에서 꺼져 있으면 기존대로 시작하지 않는다). 같은 값이 연달아 오면 아무것도 하지 않는다.

왜 `stopItda`/`startItda`를 그대로 부르지 않는가: `_layout.tsx`의 `useItdaLifecycle`이 앱이 앞으로 돌아올 때마다 `startItda()`를 부른다. 수업 마이크가 켜진 채 잠깐 다른 앱을 다녀오면 잇다가 다시 마이크를 쥔다(설계서 §8 E2E 6). 플래그를 `startItda` 안에서 확인하면 `_layout`을 고치지 않고 막을 수 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

`__tests__/itdaLecturePause.test.ts`:

```ts
import { pauseItdaForLecture, startItda } from "@/services/itda";

// 잇다 본체는 음성 인식 네이티브 모듈을 쓴다 — 엔진만 가짜로 바꿔 시작·중지 호출을 센다
const mockEngine = { start: jest.fn(async () => undefined), stop: jest.fn(), pause: jest.fn(), isRunning: false };

jest.mock("@/services/itdaVoiceEngine", () => ({
  ItdaVoiceEngine: jest.fn().mockImplementation(() => mockEngine),
}));
jest.mock("@ieum/itda", () => ({
  CapsuleSession: jest.fn().mockImplementation(() => ({ reset: jest.fn(), handle: jest.fn(), pendingQuestion: null })),
  ItdaCapsule: jest.fn(),
  NluPipeline: { create: jest.fn() },
}));
jest.mock("@/services/nlModel", () => ({ nlModelJson: {} }));
jest.mock("@/services/tactileDevice", () => ({ tactileDevice: {} }));
jest.mock("@/a11y", () => ({ speakAsItdaAsync: jest.fn(async () => undefined), stopSpeaking: jest.fn() }));
jest.mock("@/stores/settingsStore", () => ({ useSettingsStore: { getState: () => ({ itdaEnabled: true }) } }));
jest.mock("expo-router", () => ({ router: {} }));

async function flushMicrotasks(times = 5): Promise<void> {
  for (let i = 0; i < times; i += 1) await Promise.resolve();
}

beforeEach(async () => {
  pauseItdaForLecture(false); // 이전 테스트의 막음을 푼다
  await startItda(); // 잇다 인스턴스를 만들어 둔다 (stopItda는 인스턴스가 있어야 동작한다)
  await flushMicrotasks();
  jest.clearAllMocks();
});

describe("pauseItdaForLecture", () => {
  it("쉬게 하면 잇다를 멈춘다", () => {
    pauseItdaForLecture(true);

    expect(mockEngine.stop).toHaveBeenCalledTimes(1);
  });

  it("쉬는 동안에는 앱 복귀 등으로 startItda가 불려도 시작하지 않는다", async () => {
    pauseItdaForLecture(true);

    await startItda();

    expect(mockEngine.start).not.toHaveBeenCalled();
  });

  it("쉼을 풀면 잇다를 다시 시작한다", async () => {
    pauseItdaForLecture(true);

    pauseItdaForLecture(false);
    await flushMicrotasks();

    expect(mockEngine.start).toHaveBeenCalledTimes(1);
  });

  it("같은 값이 연달아 오면 아무것도 하지 않는다", async () => {
    pauseItdaForLecture(false);
    await flushMicrotasks();
    expect(mockEngine.start).not.toHaveBeenCalled();

    pauseItdaForLecture(true);
    pauseItdaForLecture(true);
    expect(mockEngine.stop).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/itdaLecturePause.test.ts`
Expected: FAIL — `pauseItdaForLecture is not a function`

- [ ] **Step 3: 구현**

`src/services/itda.ts`에서 `startItda` 정의 바로 위에 추가:

```ts
/**
 * 수업에서 내 마이크가 켜져 있는 동안 잇다는 쉰다 — 한 마이크를 두고 음성 인식과 수업 송출이 다투지 않게.
 * 앱이 앞으로 돌아올 때 `_layout`이 부르는 startItda도 이 동안에는 무시한다.
 */
let pausedForLecture = false;
```

`startItda` 본문 첫 줄에 추가(기존 `itdaEnabled` 확인보다 앞):

```ts
export async function startItda(): Promise<void> {
  if (pausedForLecture) return;
  if (!useSettingsStore.getState().itdaEnabled) return;
  // … 기존 본문 그대로
```

파일 끝에 추가:

```ts
/** 수업 마이크를 켤 때 true, 끄거나 발언이 회수되거나 방을 떠날 때 false. 같은 값이 연달아 오면 무시한다 */
export function pauseItdaForLecture(paused: boolean): void {
  if (pausedForLecture === paused) return;
  pausedForLecture = paused;
  if (paused) stopItda();
  else void startItda();
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/itdaLecturePause.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: PASS, 타입 오류 없음

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/src/services/itda.ts front/apps/mobile/__tests__/itdaLecturePause.test.ts
git commit -m "feat(lecture): 수업 마이크를 쓰는 동안 잇다가 마이크를 비켜 준다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 발언 허용·회수 감지, 상태 스토어, 오디오 프로필

**Files:**
- Create: `front/apps/mobile/src/stores/lectureVoiceStore.ts`
- Modify: `front/apps/mobile/src/services/liveLecture.ts`
- Modify: `front/apps/mobile/src/app/class/waiting.tsx` (타입을 맞추려고 `onSpeakingChanged`만 먼저 추가 — 낭독은 Task 6)
- Test: `front/apps/mobile/__tests__/liveLecture.test.ts`

**Interfaces:**
- Consumes: `canSpeak`, `handFromAttributes` (Task 1)
- Produces:
  - `LiveLectureHandlers.onSpeakingChanged: (allowed: boolean) => void` — **필수** 필드. 살아 있는 세션에서 발언 가능 여부가 **바뀔 때만** 한 번씩 부른다
  - `useLectureVoiceStore` — `{ speakingAllowed: boolean; micOn: boolean; handUp: boolean; set(patch); reset() }`. `liveLecture`만 쓴다. 세션이 없으면 전부 `false`
  - 내부 헬퍼(Task 4가 쓴다): `isLive(current)`, `syncVoice(current)`, `applySpeaking(current, allowed)`, `Session`의 새 필드 `speakingAllowed`, `micOn`, `handUp`, `micBusy`

- [ ] **Step 1: 스토어 작성**

`src/stores/lectureVoiceStore.ts`:

```ts
import { create } from "zustand";

/**
 * 수업 중 내 발언 상태 — 풀이 화면이 구독해 손들기·마이크 버튼을 그린다.
 *
 * 값은 `services/liveLecture.ts`만 바꾼다. 방을 떠나면 처음 값으로 돌아간다.
 * 실제로 말할 수 있는지는 LiveKit 권한이 원천이고, 여기는 그 사본이다.
 */
interface LectureVoiceState {
  /** 선생님이 발언을 허용했다 (LiveKit canPublish + MICROPHONE) */
  speakingAllowed: boolean;
  /** 내 마이크가 수업에 송출 중이다 */
  micOn: boolean;
  /** 손을 들었다 (참가자 속성 hand=up) */
  handUp: boolean;
  set: (patch: Partial<Pick<LectureVoiceState, "speakingAllowed" | "micOn" | "handUp">>) => void;
  reset: () => void;
}

const INITIAL = { speakingAllowed: false, micOn: false, handUp: false };

export const useLectureVoiceStore = create<LectureVoiceState>()((set) => ({
  ...INITIAL,
  set: (patch) => set(patch),
  reset: () => set(INITIAL),
}));
```

- [ ] **Step 2: 테스트 목을 넓히고 실패하는 테스트 작성**

`__tests__/liveLecture.test.ts`를 고친다.

(a) import에 스토어 추가:

```ts
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";
```

(b) `class MockRoom` 위에 로컬 참가자 목을 추가하고 `MockRoom`에 필드를 단다:

```ts
class MockLocalParticipant {
  permissions: { canPublish: boolean; canPublishSources: number[] } | undefined = {
    canPublish: false,
    canPublishSources: [],
  };
  attributes: Record<string, string> = {};
  setAttributes = jest.fn(async (patch: Record<string, string>) => {
    this.attributes = { ...this.attributes, ...patch };
  });
  setMicrophoneEnabled = jest.fn(async (_enabled: boolean) => undefined);
}
```

`class MockRoom` 안 `metadata` 선언 아래:

```ts
  localParticipant = new MockLocalParticipant();
```

(c) `jest.mock("livekit-client", …)`의 `RoomEvent`를 넓힌다:

```ts
jest.mock("livekit-client", () => ({
  Room: jest.fn().mockImplementation(() => new MockRoom()),
  RoomEvent: {
    RoomMetadataChanged: "roomMetadataChanged",
    Disconnected: "disconnected",
    ParticipantPermissionsChanged: "participantPermissionsChanged",
    ParticipantAttributesChanged: "participantAttributesChanged",
  },
}));
```

(d) 잇다 목 추가(`liveLecture`가 Task 4에서 `./itda`를 불러온다 — 지금 넣어 두면 실제 잇다 모듈이 테스트에 올라오지 않는다):

```ts
jest.mock("@/services/itda", () => ({ pauseItdaForLecture: jest.fn() }));
```

(e) `handlers()`에 새 핸들러 추가:

```ts
function handlers() {
  return { onOpen: jest.fn(), onExit: jest.fn(), onSpeakingChanged: jest.fn() };
}
```

(f) `flushMicrotasks` 아래에 권한 헬퍼 추가:

```ts
const MIC = 2;

/** 백엔드가 발언을 허용했을 때 LiveKit이 로컬 참가자에게 내는 이벤트를 흉내 낸다 */
function allow(room: MockRoom): void {
  const prev = room.localParticipant.permissions;
  room.localParticipant.permissions = { canPublish: true, canPublishSources: [MIC] };
  room.localParticipant.attributes = { ...room.localParticipant.attributes, hand: "down", speaking: "allowed" };
  room.emit("participantPermissionsChanged", prev, room.localParticipant);
}

function revoke(room: MockRoom): void {
  const prev = room.localParticipant.permissions;
  room.localParticipant.permissions = { canPublish: false, canPublishSources: [] };
  room.localParticipant.attributes = { ...room.localParticipant.attributes, hand: "down", speaking: "none" };
  room.emit("participantPermissionsChanged", prev, room.localParticipant);
}

async function joinOpen(h = handlers()) {
  mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
  await liveLecture.join("42", h);
  return { h, room: mockRooms[0]! };
}
```

(g) `beforeEach` 끝에 스토어 초기화 확인용으로 아무것도 더하지 않는다 — `leave()`가 스토어를 비워야 한다(아래 테스트가 검증).

(h) 파일 끝에 새 describe 추가:

```ts
describe("liveLecture 발언 권한", () => {
  it("입장 직후에는 말할 수 없고 알리지 않는다", async () => {
    const { h } = await joinOpen();

    expect(h.onSpeakingChanged).not.toHaveBeenCalled();
    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false, handUp: false });
  });

  it("선생님이 허용하면 한 번 알리고 상태를 바꾼다", async () => {
    const { h, room } = await joinOpen();

    allow(room);
    allow(room); // 같은 권한이 다시 와도 되풀이하지 않는다

    expect(h.onSpeakingChanged).toHaveBeenCalledTimes(1);
    expect(h.onSpeakingChanged).toHaveBeenCalledWith(true);
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(true);
  });

  it("선생님이 거두면 알리고 상태를 되돌린다", async () => {
    const { h, room } = await joinOpen();
    allow(room);

    revoke(room);

    expect(h.onSpeakingChanged).toHaveBeenLastCalledWith(false);
    expect(h.onSpeakingChanged).toHaveBeenCalledTimes(2);
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });

  it("다른 참가자의 권한 변화는 무시한다", async () => {
    const { h, room } = await joinOpen();
    const other = { permissions: { canPublish: true, canPublishSources: [MIC] }, attributes: {} };

    room.emit("participantPermissionsChanged", undefined, other);

    expect(h.onSpeakingChanged).not.toHaveBeenCalled();
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });

  it("허용되면 통화 프로필, 거두면 미디어 프로필로 오디오를 바꾼다", async () => {
    const { room } = await joinOpen();
    mockedAudio.configureAudio.mockClear();

    allow(room);
    expect(mockedAudio.configureAudio).toHaveBeenLastCalledWith({
      android: { audioTypeOptions: AndroidAudioTypePresets.communication },
    });

    revoke(room);
    expect(mockedAudio.configureAudio).toHaveBeenLastCalledWith({
      android: { audioTypeOptions: AndroidAudioTypePresets.media },
    });
  });

  it("내 hand 속성이 바뀌면 손 상태를 따라간다", async () => {
    const { room } = await joinOpen();

    room.localParticipant.attributes = { hand: "up" };
    room.emit("participantAttributesChanged", { hand: "up" }, room.localParticipant);
    expect(useLectureVoiceStore.getState().handUp).toBe(true);

    room.localParticipant.attributes = { hand: "down" };
    room.emit("participantAttributesChanged", { hand: "down" }, room.localParticipant);
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("허용되면 속성 이벤트보다 먼저 와도 손이 내려간 것으로 보인다", async () => {
    const { room } = await joinOpen();
    useLectureVoiceStore.getState().set({ handUp: true });
    room.localParticipant.attributes = { hand: "up" };
    room.emit("participantAttributesChanged", { hand: "up" }, room.localParticipant);

    allow(room);

    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("다시 입장하면 이전 방의 권한 이벤트는 누구에게도 알리지 않는다", async () => {
    const { h: first, room: oldRoom } = await joinOpen();
    const second = handlers();
    await liveLecture.join("42", second);

    allow(oldRoom);

    expect(first.onSpeakingChanged).not.toHaveBeenCalled();
    expect(second.onSpeakingChanged).not.toHaveBeenCalled();
    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });

  it("방을 떠나면 발언 상태를 비운다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await liveLecture.leave();

    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false, handUp: false });
  });

  it("방이 삭제돼도 발언 상태를 비운다", async () => {
    const { room } = await joinOpen();
    allow(room);

    room.emit("disconnected", 5);

    expect(useLectureVoiceStore.getState().speakingAllowed).toBe(false);
  });
});
```

주의: "다시 입장하면…" 테스트는 기존 구현의 `discardSilently`가 `removeAllListeners()`를 부르므로 목 방에서도 이벤트가 사라진다. 그래도 넣는 이유는 앞으로 리스너 해제가 빠져도 `isLive` 가드로 막히는지 지키기 위해서다 — Step 4 구현이 두 겹(리스너 해제 + `isLive`)을 모두 가진다.

- [ ] **Step 3: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveLecture.test.ts`
Expected: 새 describe의 테스트들 FAIL (`onSpeakingChanged` 호출 없음, 스토어 값 그대로). 기존 테스트는 PASS 유지

- [ ] **Step 4: 구현**

`src/services/liveLecture.ts`:

(a) import 교체:

```ts
import { AndroidAudioTypePresets, AudioSession } from "@livekit/react-native";
import { ApiError } from "@ieum/api-client";
import type { MediaToken } from "@ieum/contracts";
import { Room, RoomEvent } from "livekit-client";
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";
import { classService } from "./classService";
import { canSpeak, classifyDisconnect, handFromAttributes, isLectureOpen, type RoomExit } from "./lectureSignals";
```

(b) 핸들러와 세션 타입:

```ts
export interface LiveLectureHandlers {
  /** 수업이 시작됐다 (접속 1회당 최대 1번) */
  onOpen: () => void;
  /** 방에서 나왔다 — ended: 강사가 종료, lost: 음성 연결 끊김, left: 사용자가 나감 (1번) */
  onExit: (exit: RoomExit) => void;
  /** 선생님이 발언을 허용(true)하거나 거뒀다(false). 값이 바뀔 때만 부른다 — 화면을 옮겨도 불린다 */
  onSpeakingChanged: (allowed: boolean) => void;
}

interface Session {
  lectureId: string;
  room: Room;
  handlers: LiveLectureHandlers;
  opened: boolean;
  exited: boolean;
  leavingByUser: boolean;
  /** 이 세션을 만든 join() 호출의 세대 — 오디오 세션 소유권 확인에 쓴다 */
  generation: number;
  /** 선생님이 발언을 허용했다 — LiveKit 권한의 사본 */
  speakingAllowed: boolean;
  /** 내 마이크가 송출 중이다 */
  micOn: boolean;
  /** 손을 들었다 */
  handUp: boolean;
  /** setMicrophone이 진행 중이다 — 겹친 켜기·끄기를 막는다 */
  micBusy: boolean;
}
```

모듈 주석의 "학습자 토큰은 수신 전용이라 마이크를 publish하지 않는다" 문장을 다음으로 바꾼다:

```ts
 * 학습자는 입장 때 말할 수 없다. 선생님이 발언을 허용하면(LiveKit 권한 변경) 스스로 마이크를 켠다.
 * 원격 오디오(선생님·다른 학생)는 자동 구독·재생된다.
```

(c) `markOpen` 위에 헬퍼 추가:

```ts
function isLive(current: Session): boolean {
  return !current.exited && session === current;
}

/** 살아 있는 세션의 발언 상태를 화면용 스토어에 옮긴다 */
function syncVoice(current: Session): void {
  if (!isLive(current)) return;
  useLectureVoiceStore.getState().set({
    speakingAllowed: current.speakingAllowed,
    micOn: current.micOn,
    handUp: current.handUp,
  });
}

/**
 * 말할 때는 통화 프로필(에코 제거·마이크 경로), 듣기만 할 때는 미디어 프로필(스피커·미디어 음량).
 * 이 세대가 오디오를 소유할 때만 바꾼다 — 낡은 세션이 새 세션의 오디오를 건드리지 않게.
 */
function applyAudioProfile(current: Session, speaking: boolean): void {
  if (audioOwner !== current.generation) return;
  const audioTypeOptions = speaking ? AndroidAudioTypePresets.communication : AndroidAudioTypePresets.media;
  void AudioSession.configureAudio({ android: { audioTypeOptions } }).catch(() => undefined);
}

/** 로컬 참가자의 발언 권한이 바뀌었다 — 값이 실제로 바뀔 때만 반영하고 알린다 */
function applySpeaking(current: Session, allowed: boolean): void {
  if (!isLive(current) || current.speakingAllowed === allowed) return;
  current.speakingAllowed = allowed;
  // 허용·회수 때 백엔드가 hand를 down으로 바꾼다. 속성 이벤트가 늦게 와도 버튼이 맞게 보이게 미리 내린다
  current.handUp = false;
  applyAudioProfile(current, allowed);
  syncVoice(current);
  current.handlers.onSpeakingChanged(allowed);
}
```

(d) `finish`에서 세션을 비울 때 스토어도 비운다:

```ts
function finish(current: Session, exit: RoomExit): void {
  if (current.exited) return;
  const wasLive = session === current;
  current.exited = true;
  current.room.removeAllListeners();
  if (session === current) session = null;
  if (wasLive) useLectureVoiceStore.getState().reset();
  releaseAudio(current.generation);
  if (wasLive) current.handlers.onExit(exit);
}
```

(e) `discardSilently`도 같다:

```ts
async function discardSilently(previous: Session): Promise<void> {
  // 이전 핸들러가 불리지 않게 먼저 표시하고 끊는다
  previous.exited = true;
  previous.room.removeAllListeners();
  if (session === previous) {
    session = null;
    useLectureVoiceStore.getState().reset();
  }
  await previous.room.disconnect();
  // 새 입장이 실패해도 이전 방의 오디오가 켜진 채 남지 않게 한다 (소유 확인으로 새 세대의 오디오는 건드리지 않는다)
  releaseAudio(previous.generation);
}
```

(f) `join()`에서 `current`를 만드는 줄을 바꾸고, 기존 두 `room.on` 뒤에 리스너 두 개를 더한다:

```ts
    const current: Session = {
      lectureId,
      room,
      handlers,
      opened: false,
      exited: false,
      leavingByUser: false,
      generation,
      speakingAllowed: false,
      micOn: false,
      handUp: false,
      micBusy: false,
    };

    room.on(RoomEvent.RoomMetadataChanged, (metadata: string) => {
      if (isLectureOpen(metadata)) markOpen(current);
    });
    room.on(RoomEvent.Disconnected, (reason?: number) => {
      finish(current, classifyDisconnect(reason, current.leavingByUser));
    });
    // 선생님의 발언 허용·회수는 로컬 참가자의 권한 변경으로 온다 (speaking 속성은 표시용이라 보지 않는다)
    room.on(RoomEvent.ParticipantPermissionsChanged, (_prev, participant) => {
      if (participant === room.localParticipant) applySpeaking(current, canSpeak(participant.permissions));
    });
    room.on(RoomEvent.ParticipantAttributesChanged, (_changed, participant) => {
      if (participant !== room.localParticipant || !isLive(current)) return;
      const handUp = handFromAttributes(participant.attributes) === "up";
      if (current.handUp === handUp) return;
      current.handUp = handUp;
      syncVoice(current);
    });
```

(g) `session = current;` 바로 다음 줄:

```ts
    session = current;
    syncVoice(current); // 새 세션은 말할 수 없는 상태로 시작한다
```

(h) 접속 성공 뒤, 마지막 `if (lecture.status === "OPEN" || …) markOpen(current);` 바로 앞에:

```ts
    // 토큰 권한의 스냅샷 — 학습자 토큰은 canPublish=false라 보통 아무 일도 없다
    applySpeaking(current, canSpeak(room.localParticipant.permissions));
```

(i) `src/app/class/waiting.tsx`의 `liveLecture.join(validLectureId, { … })` 객체에 타입을 맞추는 자리만 먼저 둔다(`onExit` 다음):

```ts
        onSpeakingChanged: () => {
          // 허용·회수 낭독은 Task 6에서 채운다
        },
```

- [ ] **Step 5: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveLecture.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: 기존·신규 전부 PASS, 타입 오류 없음. `room.on`의 리스너 인자는 `RoomEventCallbacks`에서 추론된다(`participant: RemoteParticipant | LocalParticipant`) — 타입을 손으로 달지 않는다

- [ ] **Step 6: Commit**

```bash
git add front/apps/mobile/src/stores/lectureVoiceStore.ts front/apps/mobile/src/services/liveLecture.ts front/apps/mobile/src/app/class/waiting.tsx front/apps/mobile/__tests__/liveLecture.test.ts
git commit -m "feat(lecture): 선생님의 발언 허용과 회수를 감지해 상태와 오디오 프로필을 바꾼다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 손들기·마이크 켜기와 퇴장 정리

**Files:**
- Modify: `front/apps/mobile/src/services/liveLecture.ts`
- Test: `front/apps/mobile/__tests__/liveLecture.test.ts`

**Interfaces:**
- Consumes: `pauseItdaForLecture` (Task 2), `isLive`/`syncVoice`/`applySpeaking`/`Session.micOn·micBusy·handUp·speakingAllowed` (Task 3)
- Produces:
  - `liveLecture.raiseHand(up: boolean): Promise<boolean>` — 살아 있는 세션에서 `setAttributes({hand: "up"|"down"})`. 발언이 허용된 동안 `up=true`는 거절(false). 실패·세션 없음은 false
  - `liveLecture.setMicrophone(on: boolean): Promise<boolean>` — 켜기는 발언이 허용됐을 때만. 이미 그 상태면 true. 진행 중 겹친 호출·실패·세션 없음·켜는 사이 회수는 false. 켜기 전에 잇다를 쉬게 하고, 끄거나 실패하면 돌려준다
  - 발언 회수·`leave()`·방 삭제·끊김·재입장 시: 마이크 상태를 끄고 `pauseItdaForLecture(false)`

- [ ] **Step 1: 실패하는 테스트 작성**

`__tests__/liveLecture.test.ts` 위쪽 import 아래에 추가:

```ts
import { pauseItdaForLecture } from "@/services/itda";

const mockedPauseItda = pauseItdaForLecture as unknown as jest.Mock;
```

파일 끝에 추가:

```ts
describe("liveLecture 손들기·마이크", () => {
  it("세션이 없으면 손들기·마이크 모두 거절한다", async () => {
    await expect(liveLecture.raiseHand(true)).resolves.toBe(false);
    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);
  });

  it("손을 들면 hand 속성을 up으로 보내고 상태를 바꾼다", async () => {
    const { room } = await joinOpen();

    await expect(liveLecture.raiseHand(true)).resolves.toBe(true);

    expect(room.localParticipant.setAttributes).toHaveBeenCalledWith({ hand: "up" });
    expect(useLectureVoiceStore.getState().handUp).toBe(true);
  });

  it("손을 내리면 hand 속성을 down으로 보낸다", async () => {
    const { room } = await joinOpen();
    await liveLecture.raiseHand(true);

    await expect(liveLecture.raiseHand(false)).resolves.toBe(true);

    expect(room.localParticipant.setAttributes).toHaveBeenLastCalledWith({ hand: "down" });
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("발언이 허용된 동안에는 손을 들지 않는다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await expect(liveLecture.raiseHand(true)).resolves.toBe(false);
    expect(room.localParticipant.setAttributes).not.toHaveBeenCalled();
  });

  it("속성 전송이 실패하면 손 상태를 바꾸지 않는다", async () => {
    const { room } = await joinOpen();
    room.localParticipant.setAttributes.mockRejectedValueOnce(new Error("timeout"));

    await expect(liveLecture.raiseHand(true)).resolves.toBe(false);
    expect(useLectureVoiceStore.getState().handUp).toBe(false);
  });

  it("허용 전에는 마이크를 켜지 않는다", async () => {
    const { room } = await joinOpen();

    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);

    expect(room.localParticipant.setMicrophoneEnabled).not.toHaveBeenCalled();
    expect(mockedPauseItda).not.toHaveBeenCalledWith(true);
  });

  it("허용되면 잇다를 먼저 쉬게 한 뒤 마이크를 켠다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await expect(liveLecture.setMicrophone(true)).resolves.toBe(true);

    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenCalledWith(true);
    const pausedAt = mockedPauseItda.mock.invocationCallOrder[mockedPauseItda.mock.calls.findIndex((c) => c[0] === true)]!;
    const enabledAt = room.localParticipant.setMicrophoneEnabled.mock.invocationCallOrder[0]!;
    expect(pausedAt).toBeLessThan(enabledAt);
    expect(useLectureVoiceStore.getState().micOn).toBe(true);
  });

  it("마이크를 끄면 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    await expect(liveLecture.setMicrophone(false)).resolves.toBe(true);

    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false);
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("이미 그 상태면 다시 부르지 않고 성공으로 본다", async () => {
    const { room } = await joinOpen();
    allow(room);

    await expect(liveLecture.setMicrophone(false)).resolves.toBe(true);
    expect(room.localParticipant.setMicrophoneEnabled).not.toHaveBeenCalled();
  });

  it("마이크 켜기가 실패하면 꺼진 상태로 두고 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    room.localParticipant.setMicrophoneEnabled.mockRejectedValueOnce(new Error("NotAllowedError"));

    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);

    expect(mockedPauseItda).toHaveBeenLastCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("켜는 중에 겹쳐 누르면 나중 요청은 거절한다", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const first = liveLecture.setMicrophone(true);
    await expect(liveLecture.setMicrophone(false)).resolves.toBe(false);
    gate.resolve(undefined);

    await expect(first).resolves.toBe(true);
    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenCalledTimes(1);
  });

  it("선생님이 거두면 마이크를 끄고 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    revoke(room);

    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false);
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false });
    // 거둔 뒤에는 다시 켤 수 없다
    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);
  });

  it("켜는 사이 거둬지면 켜진 채 남기지 않는다", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const turningOn = liveLecture.setMicrophone(true);
    revoke(room);
    gate.resolve(undefined);

    await expect(turningOn).resolves.toBe(false);
    expect(room.localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false);
    expect(mockedPauseItda).toHaveBeenLastCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("마이크를 켠 채 나가면 잇다를 돌려주고 상태를 비운다", async () => {
    const { room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    await liveLecture.leave();

    expect(room.disconnect).toHaveBeenCalled(); // disconnect가 로컬 트랙을 멈춘다
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });

  it("마이크를 켠 채 수업이 끝나도 잇다를 돌려준다", async () => {
    const { h, room } = await joinOpen();
    allow(room);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    room.emit("disconnected", 5);

    expect(h.onExit).toHaveBeenCalledWith("ended");
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
  });

  it("마이크를 켠 채 다시 입장하면 이전 방의 마이크를 정리하고 새 방은 꺼진 채 시작한다", async () => {
    const { room: oldRoom } = await joinOpen();
    allow(oldRoom);
    await liveLecture.setMicrophone(true);
    mockedPauseItda.mockClear();

    await liveLecture.join("42", handlers());

    expect(oldRoom.disconnect).toHaveBeenCalled();
    expect(mockedPauseItda).toHaveBeenCalledWith(false);
    expect(useLectureVoiceStore.getState()).toMatchObject({ speakingAllowed: false, micOn: false });
    await expect(liveLecture.setMicrophone(true)).resolves.toBe(false);
  });

  it("켜는 사이 방을 떠나면 true를 돌려주지 않고 잇다를 돌려준다", async () => {
    const { room } = await joinOpen();
    allow(room);
    const gate = deferred<undefined>();
    room.localParticipant.setMicrophoneEnabled.mockImplementationOnce(() => gate.promise);

    const turningOn = liveLecture.setMicrophone(true);
    await liveLecture.leave();
    gate.resolve(undefined);

    await expect(turningOn).resolves.toBe(false);
    expect(mockedPauseItda).toHaveBeenLastCalledWith(false);
    expect(useLectureVoiceStore.getState().micOn).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveLecture.test.ts`
Expected: FAIL — `liveLecture.raiseHand is not a function` / `setMicrophone is not a function` (타입 오류로 실패해도 된다)

- [ ] **Step 3: 구현**

`src/services/liveLecture.ts`:

(a) import에 추가:

```ts
import { pauseItdaForLecture } from "./itda";
```

(b) `applySpeaking` 위에 헬퍼 추가:

```ts
/**
 * 내 마이크 상태를 끄고 잇다에게 마이크를 돌려준다.
 * unpublish=true면 트랙도 내린다(회수 때 — LiveKit이 이미 내렸어도 무해하다).
 * 방을 떠날 때는 disconnect가 로컬 트랙을 멈추므로 false로 부른다.
 */
function releaseMic(current: Session, unpublish: boolean): void {
  const wasOn = current.micOn;
  current.micOn = false;
  if (wasOn && unpublish) void current.room.localParticipant.setMicrophoneEnabled(false).catch(() => undefined);
  pauseItdaForLecture(false); // 쉬고 있지 않았다면 아무 일도 없다
}
```

(c) `applySpeaking`에서 회수 때 마이크를 내린다 — `current.handUp = false;` 다음 줄에:

```ts
  if (!allowed) releaseMic(current, true);
```

(d) `finish`에서 `current.room.removeAllListeners();` 다음 줄에:

```ts
  releaseMic(current, false);
```

(e) `discardSilently`에서 `previous.room.removeAllListeners();` 다음 줄에:

```ts
  releaseMic(previous, false);
```

(f) `liveLecture` 객체에 `leave()` 다음, `currentLectureId()` 앞에 두 메서드를 추가:

```ts
  /** 손들기(true)·내리기(false). 발언이 허용된 동안에는 들지 않는다. 선생님께 전해지면 true */
  async raiseHand(up: boolean): Promise<boolean> {
    const current = session;
    if (!current || !isLive(current)) return false;
    if (up && current.speakingAllowed) return false;
    try {
      await current.room.localParticipant.setAttributes({ hand: up ? "up" : "down" });
    } catch {
      return false;
    }
    if (!isLive(current)) return false;
    current.handUp = up;
    syncVoice(current);
    return true;
  },

  /**
   * 내 마이크를 켜고(true) 끈다(false). 켜기는 선생님이 허용했을 때만 된다.
   * 원하는 상태가 되면 true, 허용 안 됨·겹친 호출·실패·그 사이 회수나 퇴장이면 false.
   * 마이크 런타임 권한(RECORD_AUDIO)은 부르는 쪽이 먼저 확인한다(micPermission).
   */
  async setMicrophone(on: boolean): Promise<boolean> {
    const current = session;
    if (!current || !isLive(current) || current.micBusy) return false;
    if (on && !current.speakingAllowed) return false;
    if (current.micOn === on) return true;

    current.micBusy = true;
    // 잇다(음성 인식)가 마이크를 먼저 놓아야 수업 마이크가 소리를 얻는다
    if (on) pauseItdaForLecture(true);
    try {
      await current.room.localParticipant.setMicrophoneEnabled(on);
    } catch {
      current.micBusy = false;
      if (on) pauseItdaForLecture(false);
      return false;
    }
    current.micBusy = false;

    // 켜는 사이 방을 떠났거나 선생님이 거뒀다 — 켜진 채 남기지 않는다
    if (on && (!isLive(current) || !current.speakingAllowed)) {
      void current.room.localParticipant.setMicrophoneEnabled(false).catch(() => undefined);
      pauseItdaForLecture(false);
      return false;
    }
    if (!isLive(current)) return false;

    current.micOn = on;
    if (!on) pauseItdaForLecture(false);
    syncVoice(current);
    return true;
  },
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveLecture.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: 전부 PASS, 타입 오류 없음

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/src/services/liveLecture.ts front/apps/mobile/__tests__/liveLecture.test.ts
git commit -m "feat(lecture): 손들기와 허용된 동안의 마이크 켜기·끄기를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 마이크 권한 요청과 버튼 문구 (순수)

**Files:**
- Create: `front/apps/mobile/src/services/micPermission.ts`
- Create: `front/apps/mobile/src/services/lectureVoiceControls.ts`
- Test: `front/apps/mobile/__tests__/micPermission.test.ts`, `front/apps/mobile/__tests__/lectureVoiceControls.test.ts`

**Interfaces:**
- Produces:
  - `ensureMicPermission(): Promise<boolean>` — 허용이면 true. 이미 허용됐으면 대화상자 없이 true. 요청이 예외를 던지면 false
  - `interface LectureVoiceView { speakingAllowed: boolean; micOn: boolean; handUp: boolean }`
  - `lectureVoiceControls(view: LectureVoiceView): { hand: { label: string; hint: string } | null; mic: { label: string; hint: string; disabled: boolean }; status: string }`
  - 문구 상수: `SPEAKING_ALLOWED_MESSAGE`, `SPEAKING_REVOKED_MESSAGE`, `MIC_LOCKED_HINT`, `MIC_PERMISSION_DENIED_MESSAGE`, `MIC_ON_MESSAGE`, `MIC_OFF_MESSAGE`, `MIC_FAILED_MESSAGE`, `HAND_UP_MESSAGE`, `HAND_DOWN_MESSAGE`, `HAND_FAILED_MESSAGE`

- [ ] **Step 1: 실패하는 테스트 작성**

`__tests__/micPermission.test.ts`:

```ts
import { AudioModule } from "expo-audio";
import { ensureMicPermission } from "@/services/micPermission";

jest.mock("expo-audio", () => ({
  AudioModule: { requestRecordingPermissionsAsync: jest.fn() },
}));

const request = AudioModule.requestRecordingPermissionsAsync as unknown as jest.Mock;

beforeEach(() => request.mockReset());

describe("ensureMicPermission", () => {
  it("허용하면 true", async () => {
    request.mockResolvedValue({ granted: true, status: "granted", canAskAgain: true, expires: "never" });
    await expect(ensureMicPermission()).resolves.toBe(true);
  });

  it("거부하면 false", async () => {
    request.mockResolvedValue({ granted: false, status: "denied", canAskAgain: false, expires: "never" });
    await expect(ensureMicPermission()).resolves.toBe(false);
  });

  it("요청 자체가 실패해도 false", async () => {
    request.mockRejectedValue(new Error("no activity"));
    await expect(ensureMicPermission()).resolves.toBe(false);
  });
});
```

`__tests__/lectureVoiceControls.test.ts`:

```ts
import {
  lectureVoiceControls,
  MIC_LOCKED_HINT,
  SPEAKING_ALLOWED_MESSAGE,
  SPEAKING_REVOKED_MESSAGE,
  MIC_PERMISSION_DENIED_MESSAGE,
} from "@/services/lectureVoiceControls";

describe("lectureVoiceControls", () => {
  it("허용 전: 손들기 버튼이 있고 마이크는 잠겨 있다", () => {
    const c = lectureVoiceControls({ speakingAllowed: false, micOn: false, handUp: false });
    expect(c.hand?.label).toBe("손들기");
    expect(c.mic).toEqual({ label: "마이크 켜기", hint: MIC_LOCKED_HINT, disabled: true });
    expect(c.status).toBe("");
  });

  it("손을 든 상태: 손 내리기", () => {
    const c = lectureVoiceControls({ speakingAllowed: false, micOn: false, handUp: true });
    expect(c.hand?.label).toBe("손 내리기");
    expect(c.status).toBe("손 든 상태");
  });

  it("허용되면 손들기를 숨기고 마이크를 켤 수 있다", () => {
    const c = lectureVoiceControls({ speakingAllowed: true, micOn: false, handUp: false });
    expect(c.hand).toBeNull();
    expect(c.mic.label).toBe("마이크 켜기");
    expect(c.mic.disabled).toBe(false);
    expect(c.status).toBe("발언 허용됨, 마이크 꺼짐");
  });

  it("마이크가 켜져 있으면 끄기", () => {
    const c = lectureVoiceControls({ speakingAllowed: true, micOn: true, handUp: false });
    expect(c.mic.label).toBe("마이크 끄기");
    expect(c.mic.disabled).toBe(false);
    expect(c.status).toBe("내 마이크 켜짐");
  });

  it("설계서 문구를 그대로 쓴다", () => {
    expect(SPEAKING_ALLOWED_MESSAGE).toBe("선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요.");
    expect(SPEAKING_REVOKED_MESSAGE).toBe("선생님이 발언을 마쳤어요.");
    expect(MIC_LOCKED_HINT).toBe("선생님이 허용하면 말할 수 있어요");
    expect(MIC_PERMISSION_DENIED_MESSAGE).toBe("설정에서 마이크 권한을 허용해 주세요");
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/micPermission.test.ts __tests__/lectureVoiceControls.test.ts`
Expected: FAIL — 모듈을 찾을 수 없음

- [ ] **Step 3: 구현**

`src/services/micPermission.ts`:

```ts
import { AudioModule } from "expo-audio";

/**
 * 수업 마이크를 켜기 전에 안드로이드 마이크 권한(RECORD_AUDIO)을 확인한다.
 * 이미 허용됐으면 대화상자 없이 바로 true다. 목소리 녹음(voiceSample)과 같은 요청을 쓴다.
 */
export async function ensureMicPermission(): Promise<boolean> {
  try {
    const permission = await AudioModule.requestRecordingPermissionsAsync();
    return permission.granted;
  } catch {
    return false;
  }
}
```

`src/services/lectureVoiceControls.ts`:

```ts
/** 수업 발언 버튼의 라벨·힌트와 안내 문구 — 화면 밖에서 테스트하려고 순수 함수로 둔다 */

export const SPEAKING_ALLOWED_MESSAGE = "선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요.";
export const SPEAKING_REVOKED_MESSAGE = "선생님이 발언을 마쳤어요.";
export const MIC_LOCKED_HINT = "선생님이 허용하면 말할 수 있어요";
export const MIC_PERMISSION_DENIED_MESSAGE = "설정에서 마이크 권한을 허용해 주세요";
/** 켜기 **전에** 읽는다 — 켠 뒤에 읽으면 안내 음성이 수업에 그대로 전해진다 */
export const MIC_ON_MESSAGE = "마이크를 켤게요. 말씀하세요.";
export const MIC_OFF_MESSAGE = "마이크를 껐어요.";
export const MIC_FAILED_MESSAGE = "마이크를 바꾸지 못했어요. 다시 눌러 주세요.";
export const HAND_UP_MESSAGE = "손을 들었어요. 선생님이 허용하면 알려 드릴게요.";
export const HAND_DOWN_MESSAGE = "손을 내렸어요.";
export const HAND_FAILED_MESSAGE = "선생님께 전하지 못했어요. 다시 눌러 주세요.";

export interface LectureVoiceView {
  speakingAllowed: boolean;
  micOn: boolean;
  handUp: boolean;
}

export interface LectureVoiceControls {
  /** 발언이 허용된 동안에는 null(숨김) */
  hand: { label: string; hint: string } | null;
  mic: { label: string; hint: string; disabled: boolean };
  /** 상태 줄 접근성 라벨에 붙일 말. 알릴 것이 없으면 "" */
  status: string;
}

export function lectureVoiceControls(view: LectureVoiceView): LectureVoiceControls {
  const hand = view.speakingAllowed
    ? null
    : view.handUp
      ? { label: "손 내리기", hint: "선생님께 한 발언 요청을 거둬요" }
      : { label: "손들기", hint: "선생님께 말하고 싶다고 알려요" };

  const mic = !view.speakingAllowed
    ? { label: "마이크 켜기", hint: MIC_LOCKED_HINT, disabled: true }
    : view.micOn
      ? { label: "마이크 끄기", hint: "누르면 내 목소리가 더 이상 전해지지 않아요", disabled: false }
      : { label: "마이크 켜기", hint: "누르면 선생님과 친구들이 내 목소리를 들어요", disabled: false };

  const status = view.speakingAllowed
    ? view.micOn
      ? "내 마이크 켜짐"
      : "발언 허용됨, 마이크 꺼짐"
    : view.handUp
      ? "손 든 상태"
      : "";

  return { hand, mic, status };
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/micPermission.test.ts __tests__/lectureVoiceControls.test.ts && pnpm --filter @ieum/mobile typecheck`
Expected: PASS, 타입 오류 없음 (`requestRecordingPermissionsAsync`의 응답 타입은 expo `PermissionResponse` — `granted: boolean`)

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/src/services/micPermission.ts front/apps/mobile/src/services/lectureVoiceControls.ts front/apps/mobile/__tests__/micPermission.test.ts front/apps/mobile/__tests__/lectureVoiceControls.test.ts
git commit -m "feat(lecture): 마이크 권한 요청과 발언 버튼 문구를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 대기·풀이 화면 연결

**Files:**
- Modify: `front/apps/mobile/src/app/class/waiting.tsx`
- Modify: `front/apps/mobile/src/app/class/play.tsx`

**Interfaces:**
- Consumes: `liveLecture.raiseHand`, `liveLecture.setMicrophone` (Task 4), `useLectureVoiceStore` (Task 3), `ensureMicPermission`, `lectureVoiceControls`와 문구 상수 (Task 5), `announce`·`speakAsync`·`hapticSuccess` (`@/a11y`)

설계 결정 — 누가 무엇을 하나:
- **낭독(허용·회수)**: 대기 화면의 `onSpeakingChanged`. 이 핸들러는 대기 화면이 풀이 화면으로 `replace`된 뒤에도 `liveLecture`에 살아 있으므로(기존 `onExit`과 같다) 학습자가 채점 화면에 있어도 알린다. `mountedRef`로 막지 **않는다**.
- **버튼 상태**: 풀이 화면이 `useLectureVoiceStore`를 구독한다. 화면이 다시 마운트돼도(채점 → 다음 문제) 스토어에서 바로 올바른 상태를 읽는다.

- [ ] **Step 1: 대기 화면 — 허용·회수 낭독**

`src/app/class/waiting.tsx`:

import 추가:

```ts
import { SPEAKING_ALLOWED_MESSAGE, SPEAKING_REVOKED_MESSAGE } from "@/services/lectureVoiceControls";
```

Task 3에서 둔 빈 `onSpeakingChanged`를 바꾼다:

```ts
        onSpeakingChanged: (allowed) => {
          // 풀이·채점 화면에 있어도 알린다 — 이 핸들러는 대기 화면이 사라진 뒤에도 불린다
          if (allowed) void hapticSuccess();
          announce(allowed ? SPEAKING_ALLOWED_MESSAGE : SPEAKING_REVOKED_MESSAGE);
        },
```

(`announce`, `hapticSuccess`는 이 파일이 이미 `@/a11y`에서 가져온다.)

- [ ] **Step 2: 풀이 화면 — 상태 구독과 동작**

`src/app/class/play.tsx`:

import 교체·추가:

```ts
import { liveLecture } from "@/services/liveLecture";
import { isUserBackAction } from "@/services/lectureSignals";
import { ensureMicPermission } from "@/services/micPermission";
import {
  HAND_DOWN_MESSAGE,
  HAND_FAILED_MESSAGE,
  HAND_UP_MESSAGE,
  lectureVoiceControls,
  MIC_FAILED_MESSAGE,
  MIC_OFF_MESSAGE,
  MIC_ON_MESSAGE,
  MIC_PERMISSION_DENIED_MESSAGE,
} from "@/services/lectureVoiceControls";
import { useLectureVoiceStore } from "@/stores/lectureVoiceStore";
import { useSessionStore } from "@/stores/sessionStore";
import { announce, hapticSuccess, speak, speakAsync } from "@/a11y";
```

`const [cells, setCells] = useState<Cell[]>([]);` 바로 아래(조기 return보다 **앞** — 훅 순서를 지킨다)에 추가:

```ts
  // 발언 상태는 liveLecture가 스토어에 쓴다. 객체로 한 번에 고르면 매번 새 참조라 값별로 고른다
  const speakingAllowed = useLectureVoiceStore((s) => s.speakingAllowed);
  const micOn = useLectureVoiceStore((s) => s.micOn);
  const handUp = useLectureVoiceStore((s) => s.handUp);
  const voice = lectureVoiceControls({ speakingAllowed, micOn, handUp });
  // 손들기·마이크 요청이 진행 중이다 — 겹쳐 누른 것을 무시한다 (버튼을 비활성으로 바꾸면 TalkBack이 "사용할 수 없음"을 끼워 읽는다)
  const voiceBusyRef = useRef(false);
```

`react` import를 `import { useEffect, useRef, useState } from "react";`로 바꾼다.

`submitAnswer` 정의 다음에 두 동작을 추가:

```ts
  const toggleHand = async () => {
    if (voiceBusyRef.current) return;
    voiceBusyRef.current = true;
    const up = !handUp;
    const ok = await liveLecture.raiseHand(up);
    voiceBusyRef.current = false;
    announce(ok ? (up ? HAND_UP_MESSAGE : HAND_DOWN_MESSAGE) : HAND_FAILED_MESSAGE);
  };

  const toggleMic = async () => {
    if (voiceBusyRef.current) return;
    voiceBusyRef.current = true;
    try {
      if (micOn) {
        const ok = await liveLecture.setMicrophone(false);
        announce(ok ? MIC_OFF_MESSAGE : MIC_FAILED_MESSAGE);
        return;
      }
      if (!(await ensureMicPermission())) {
        announce(MIC_PERMISSION_DENIED_MESSAGE);
        return;
      }
      // 켠 뒤에 읽으면 안내 음성이 수업에 그대로 전해진다 — 켜기 전에 말하고, 켜진 것은 진동으로 알린다
      await speakAsync(MIC_ON_MESSAGE);
      const ok = await liveLecture.setMicrophone(true);
      if (ok) void hapticSuccess();
      // 그 사이 선생님이 거뒀다면 회수 안내가 이미 나갔다 — 실패 안내를 겹쳐 읽지 않는다
      else if (useLectureVoiceStore.getState().speakingAllowed) announce(MIC_FAILED_MESSAGE);
    } finally {
      voiceBusyRef.current = false;
    }
  };
```

- [ ] **Step 3: 풀이 화면 — 상태 라벨과 버튼 배치**

상태 줄의 `accessibilityLabel`을 바꿔 발언 상태를 글로도 전한다(색 점만으로 전하지 않는다):

```tsx
        <View
          style={styles.statusItem}
          accessibilityLabel={`수업 중, 선생님 음성 연결됨${voice.status ? `, ${voice.status}` : ""}`}
        >
          {dot(theme.color.primary)}
          <Text style={{ fontSize: 16, fontWeight: "800", color: theme.color.text }}>수업 중</Text>
          <Text style={{ fontSize: 14, color: theme.color.textMuted, marginLeft: 6 }}>
            {voice.status || "선생님 음성"}
          </Text>
        </View>
```

`</View>`로 끝나는 `styles.statusRow` 블록 바로 다음(문제 제목 `<Text accessibilityRole="header" …>` 앞)에 발언 버튼 줄을 넣는다:

```tsx
      <View style={{ flexDirection: "row", gap: 10 }}>
        {voice.hand && (
          <View style={{ flex: 1 }}>
            <AppButton
              label={voice.hand.label}
              variant="compact"
              accessibilityHint={voice.hand.hint}
              onPress={() => void toggleHand()}
            />
          </View>
        )}
        <View style={{ flex: 1 }}>
          <AppButton
            label={voice.mic.label}
            variant={speakingAllowed ? "primary" : "compact"}
            accessibilityHint={voice.mic.hint}
            disabled={voice.mic.disabled}
            onPress={() => void toggleMic()}
          />
        </View>
      </View>
```

(`AppButton`은 `accessibilityRole="button"`, `accessibilityLabel={label}`, `accessibilityState={{ disabled }}`를 이미 단다. 라벨이 "켜기/끄기"로 바뀌어 상태를 말로 전한다.)

- [ ] **Step 4: 타입체크와 전체 테스트**

Run: `cd front && pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest`
Expected: 오류 없음, 전체 PASS

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/src/app/class/waiting.tsx front/apps/mobile/src/app/class/play.tsx
git commit -m "feat(lecture): 풀이 화면에 손들기와 마이크 버튼을 붙이고 발언 허용을 알린다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 실기기 확인 (사람과 함께)

**Files:** 없음 (확인 절차). 오디오 프로필 판정에 따라 Step 4의 대안을 적용할 수 있다.

전제: `feat/lecture-voice-api`(백엔드: 학습자 토큰 변경 + `PUT …/speaking`)가 `feat/lecture-voice`에 병합돼 있고, 이 브랜치를 그 위로 병합해 둔다(`git merge feat/lecture-voice` — 충돌 없이 로컬에서만). 강사 웹(`feat/lecture-voice-web`)이 없으면 강사 역할은 meet.livekit.io + curl로 대신한다.

- [ ] **Step 1: 개발 빌드**

네이티브 의존성·`app.json`을 바꾸지 않았으므로 **prebuild 재생성은 필요 없다**. 기존 개발 빌드가 설치된 기기에서 Metro만 띄운다:

```bash
cd C:/Users/SSAFY/Desktop/wt-voice-app/front/apps/mobile
npx expo start --dev-client
```

(기기에 개발 빌드가 없을 때만 `npx expo run:android`.) Metro를 끌 때는 그 PID로만 끈다.

- [ ] **Step 2: 로컬 backend**

`C:/Users/SSAFY/Desktop/wt-voice-api` 에서 `docker compose up -d --build backend`. 실기기면 앱의 API 주소를 PC LAN IP로 — `.env`는 사람이 고친다(에이전트는 열지 않는다).

- [ ] **Step 3: E2E (설계서 §8 수동 항목)**

준비: 학생 A(실기기 앱), 학생 B(두 번째 기기 앱, 없으면 두 번째 학습자 토큰으로 meet.livekit.io 접속), 강사(웹 또는 강사 토큰으로 meet.livekit.io + `PUT /api/v1/lectures/{id}/participants/{userId}/speaking` curl). TalkBack을 켠 상태로 진행한다.

1. A 앱 입장 → 수업 시작 → 풀이 화면. 상태 줄이 "수업 중, 선생님 음성 연결됨"으로 읽히고, "손들기" 버튼과 비활성 "마이크 켜기"(힌트 "선생님이 허용하면 말할 수 있어요")가 있다
2. A "손들기" → "손을 들었어요…" 낭독, 버튼이 "손 내리기"로 바뀐다. 강사 쪽에서 A의 `hand=up`이 보인다(웹 ✋ 또는 meet의 참가자 속성)
3. 강사 발언 허용(`{"allowed": true}`) → A 앱이 "선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요." 낭독, 손들기 버튼이 사라지고 마이크 버튼이 활성. A "마이크 켜기" → 첫 번째는 마이크 권한 대화상자 → 허용 → "마이크를 켤게요. 말씀하세요." 뒤 진동 → **강사와 학생 B가 A 목소리를 듣는다**
4. A 마이크 끄기/켜기 반복 — 매번 상태가 맞게 바뀌고 소리가 끊기고 다시 들린다
5. 강사 발언 끄기(`{"allowed": false}`) → A 마이크가 즉시 꺼지고 "선생님이 발언을 마쳤어요." 낭독, 마이크 버튼 비활성, 다시 켤 수 없다
6. 설정에서 잇다를 켠 상태로: A 마이크가 켜져 있는 동안 "하이 잇다"라고 말해도 잇다가 반응하지 않는다. 마이크를 켠 채 홈 버튼으로 나갔다 돌아와도 잇다가 다시 켜지지 않는다. 마이크를 끄거나 발언이 회수되면 "하이 잇다"가 다시 동작한다
7. 권한 거부: 앱 정보에서 마이크 권한을 끄고 3을 반복 → "설정에서 마이크 권한을 허용해 주세요" 낭독, 마이크가 켜지지 않는다
8. 마이크를 켠 채 "수업 나가기"(또는 뒤로 가기) → 강사 쪽에서 A 트랙이 사라지고, 앱에서 "하이 잇다"가 다시 동작한다
9. 기록만 할 것: 채점 화면에 있을 때 허용·회수 낭독이 들리는지, 마이크가 켜진 동안 TalkBack 낭독이 수업에 섞여 들리는지

Expected: 1~8 통과. 9는 MR 본문에 기록한다.

- [ ] **Step 4: 오디오 프로필 판정**

3~5를 하는 동안 확인한다: (a) 허용 뒤 A의 선생님 음성이 **스피커에서** 적당한 음량으로 계속 들리는가 (b) A가 말할 때 강사·B 쪽에 **에코(선생님 소리가 A 마이크로 되돌아옴)**가 없는가 (c) 회수 뒤 다시 미디어 음량으로 돌아오는가.

모두 괜찮으면 그대로 둔다. 전환이 적용되지 않거나(에코, 수화기로 나옴, 음량 버튼이 통화 음량으로 안 바뀜 등) 전환 순간 소리가 끊기면 설계서 §5가 허용한 대안 — **발언 허용과 관계없이 통신 프로필** — 을 적용한다:

`src/services/liveLecture.ts`:
- `join()`의 `configureAudio`를 통신 프로필로 바꾸고 주석을 고친다:

```ts
    // 학습자도 발언이 허용되면 마이크를 쓴다. 입장 뒤 프로필 전환은 실기기에서 적용되지 않아(Task 7 판정)
    // 처음부터 통신 프로필(에코 제거·마이크 경로)로 연다. 스피커 출력은 preferredOutputList 기본값이 고른다
    await AudioSession.configureAudio({ android: { audioTypeOptions: AndroidAudioTypePresets.communication } });
```

- `applyAudioProfile` 함수와 `applySpeaking`의 `applyAudioProfile(current, allowed);` 줄을 지운다.

`__tests__/liveLecture.test.ts`:
- "오디오 세션을 열기 전에 듣기 전용 미디어 오디오로 설정한다" 테스트의 이름을 "오디오 세션을 열기 전에 통신 프로필로 설정한다"로, 기대값을 `AndroidAudioTypePresets.communication`으로 바꾼다
- "허용되면 통화 프로필, 거두면 미디어 프로필로 오디오를 바꾼다" 테스트를 지운다

그다음 `cd front && pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest` 통과를 확인하고 커밋한다:

```bash
git add front/apps/mobile/src/services/liveLecture.ts front/apps/mobile/__tests__/liveLecture.test.ts
git commit -m "fix(lecture): 수업 오디오를 처음부터 통신 프로필로 연다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

어느 쪽을 택했는지와 근거(관찰한 증상)를 MR 본문에 적는다.

---

## 완료 확인 (MR 전)

- [ ] `cd front && pnpm --filter @ieum/mobile exec jest` 전체 PASS
- [ ] `pnpm --filter @ieum/mobile typecheck` 통과
- [ ] `docker build --target test front` 성공 (MR verify와 동일)
- [ ] `git status --short`에 `.superpowers/`·`.env*`가 스테이징되지 않았고, 변경 파일이 File Structure 표의 파일뿐이다 (`git diff --stat feat/lecture-voice...HEAD`)
- [ ] Task 7 E2E 1~8 확인, 9와 오디오 프로필 판정 결과를 MR 본문에 기록
- [ ] push하지 않는다 — MR은 사람이 `feat/lecture-voice-app` → `feat/lecture-voice`로 올린다
