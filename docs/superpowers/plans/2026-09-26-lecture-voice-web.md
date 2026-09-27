# 실시간 수업 양방향 음성 — 강사 웹 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 강사 웹 03 화면의 학생 그리드를 실제 LiveKit 참가자 목록으로 바꾸고, 손든 학생을 위로 올려 보여 주며, 학생별 "발언 허용 / 발언 끄기"로 송출 권한을 바꾸고, 허용된 학생의 목소리를 강사 브라우저에서 재생한다.

**Architecture:** 발언 API 호출은 `domains/lecture/api/lectureApi`, LiveKit 참가자 → 카드 모델 변환·정렬은 LiveKit에 의존하지 않는 순수 모듈 `domains/lecture/live/roster`, 참가자 이벤트 구독·원격 오디오 재생·자동 재생 해제는 `domains/lecture/live/instructorRoom`이 맡는다. 페이지는 이 셋을 조합만 하고, 방 콜백은 기존 `joinSeqRef` 세대 번호로 걸러 오래된 방의 이벤트를 무시한다. 발언 상태는 서버 응답이 아니라 LiveKit 이벤트(권한·속성 변경)로만 화면에 반영한다 — LiveKit 참가자 권한이 유일한 상태다.

**Tech Stack:** React 19, Vite 6, TypeScript 5.8, `@ieum/api-client`(workspace), `livekit-client@2.22.3`, vitest(node 환경)

**Spec:** `docs/superpowers/specs/2026-09-26-lecture-two-way-voice-design.md` §2, §3.3, §4, §8 / 계약: `C:/Users/SSAFY/Desktop/S15P21B201/.superpowers/sdd/voice-contract.md` / 화면 원본: 와이어프레임 `C:/Users/SSAFY/Desktop/이음 강사웹.html` 03 (학생 카드)

## Global Constraints

- 브랜치: `feat/lecture-voice-web` (`feat/lecture-voice`에서 분기), 작업 위치 `C:/Users/SSAFY/Desktop/wt-voice-web`, MR 대상 `feat/lecture-voice`. **push하지 않는다** (사람이 한다)
- 작업 범위: `front/apps/web` 만. backend·mobile·packages·lockfile은 건드리지 않는다 (의존성 추가 없음 — `livekit-client@2.22.3`은 이미 있다)
- 커밋: `feat(lecture): 한글 제목` + 빈 줄 + 정확히 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `--no-verify`·`--force` 금지
- API (계약 원문 그대로, `/api/v1` 접두는 baseUrl에 포함):
  - `PUT /api/v1/lectures/{lectureId}/participants/{userId}/speaking` body `{"allowed": true|false}` → 200 `{"userId": "<decimal string>", "allowed": true|false}`
  - 오류(body `{code, message}`): 403 `INSTRUCTOR_ACCESS_REQUIRED`, 404 `LECTURE_NOT_FOUND`, 409 `INVALID_LECTURE_STATE`, 404 `LECTURE_PARTICIPANT_NOT_FOUND`(신규 — 활성 참여자 아님), 404 `LECTURE_PARTICIPANT_NOT_CONNECTED`(신규 — OpenVidu 방에 접속해 있지 않음), 503 `MEDIA_SERVER_UNAVAILABLE`, 422/400 기존 검증 매핑
  - 멱등, DB 저장 없음
- LiveKit 참가자 규약 (계약 원문 그대로):
  - identity `user-{userId}` → `user-`를 떼서 userId, 표시 이름 = `participant.name`(username)
  - attributes: `hand` = `"up"` | `"down"`(학생이 올리고, 백엔드가 허용·회수 때 `"down"`), `speaking` = `"allowed"` | `"none"`(백엔드만, **표시 전용**)
  - 실제 송출 가능 여부 = `participant.permissions?.canPublish`
  - 학습자 토큰은 더 이상 `hidden`이 아니다 → 강사 방의 `room.remoteParticipants`에 학생이 보인다
- livekit-client 2.22.3 확인 결과 (`front/node_modules/livekit-client/dist/src` 타입 정의에서 확인):
  - `RoomEvent.ParticipantConnected (participant)`, `ParticipantDisconnected (participant, reason?)` — 퇴장 시 `remoteParticipants`에서 먼저 지운 뒤 이벤트를 낸다
  - `RoomEvent.ParticipantAttributesChanged (changedAttributes, participant)`, `ParticipantPermissionsChanged (prevPermissions, participant)` — 둘 다 로컬 참가자에도 온다(목록 재계산이라 무해)
  - `RoomEvent.ParticipantNameChanged (name, participant)`, `TrackPublished / TrackUnpublished (publication, participant)`, `TrackMuted / TrackUnmuted (publication, participant)`
  - `RoomEvent.ActiveSpeakersChanged (speakers)` — **`RoomEvent.IsSpeakingChanged`는 없다**(그것은 `ParticipantEvent`). 말하는 중은 이 이벤트 뒤 `participant.isSpeaking`으로 읽는다
  - `RoomEvent.TrackSubscribed / TrackUnsubscribed (track: RemoteTrack, publication, participant)`, `RoomEvent.AudioPlaybackStatusChanged (playing: boolean)` (값 `"audioPlaybackChanged"`)
  - `Participant`: `identity: string`, `name?: string`, `get attributes(): Readonly<Record<string, string>>`, `permissions?: ParticipantPermission`(`canPublish: boolean`), `isSpeaking: boolean`, `get isMicrophoneEnabled(): boolean`
  - `Track.Kind.Audio = "audio"`, `RemoteTrack.attach(): HTMLMediaElement`(요소를 만들고 `play()`까지 부른다 — 문서에 붙이지 않아도 재생된다), `detach(): HTMLMediaElement[]`
  - `room.startAudio(): Promise<void>`(막히면 throw), `room.canPlaybackAudio: boolean`(getter), `room.remoteParticipants: Map<string, RemoteParticipant>`
- 학생의 첫 마이크 켜기는 트랙 **게시**(`TrackPublished`), 이후 끄기/켜기는 **음소거**(`TrackMuted/Unmuted`), 발언 회수는 LiveKit이 트랙을 **내린다**(`TrackUnpublished`) → 마이크 표시는 이 여섯 이벤트 모두에서 다시 계산한다
- 답안 상태(정답·오답·점형·장치 점)는 계속 **목**이다. 실제 카드 안에서 점선 "예시" 칸으로 구분한다
- 기존 동작 유지: `joinSeqRef` 세대 번호와 매 await 뒤 확인, `joining`·`busy` 버튼 잠금, `releaseRoom`, 종료 503 처리(`setEndFailed`·세대 무효화·`releaseRoom`·`setMic(false)`·`setRoomState('disconnected')`), seq로 거른 `onState`, 연결 실패·마이크 실패 시 정리 규칙
- 디자인: 인라인 `style` + `theme`/`styles` 상수. UI 라이브러리 추가 없음
- ESLint `@typescript-eslint/no-explicit-any: error` — `any` 금지. lint **경고 0개** (`react-hooks/exhaustive-deps` 포함)
- 검증: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
- 서버를 띄웠으면 **PID로만** 멈춘다 (`netstat -ano | findstr :5173` → `taskkill //PID <pid> //F`). `taskkill /IM node.exe` 같은 이름 단위 종료 금지
- `.env*`·`*.pem` 파일은 읽지도, 고치지도 않는다

## File Structure (모두 `front/apps/web/` 아래)

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `src/domains/lecture/model/types.ts` | `SpeakingResult` 응답 타입 | Modify |
| `src/domains/lecture/api/lectureApi.ts` | `setSpeaking(lectureId, userId, allowed)` | Modify |
| `src/shared/ui/errorMessage.ts` | 신규 오류 코드 두 개의 문구 | Modify |
| `src/domains/lecture/live/roster.ts` | LiveKit 참가자(구조 타입) → `RosterStudent`, 정렬. LiveKit import 없음 | Create |
| `src/domains/lecture/live/instructorRoom.ts` | 참가자 이벤트 구독(스냅샷+콜백), 원격 오디오 attach/detach, 자동 재생 해제 | Modify |
| `src/domains/lecture/mock/liveMock.ts` | 머리 주석만 갱신(학생 명단은 실제, 목은 답안 예시) | Modify |
| `src/pages/LiveLecturePage.tsx` | 실제 학생 그리드, 발언 버튼, ✋·🎙, 빈 상태, "학생 소리 켜기" | Modify |
| `src/domains/lecture/api/__tests__/lectureApi.test.ts`, `src/shared/ui/__tests__/errorMessage.test.ts`, `src/domains/lecture/live/__tests__/roster.test.ts`, `src/domains/lecture/live/__tests__/instructorRoom.test.ts` | vitest | Modify / Create |

---

### Task 0: 작업 브랜치 준비

**Files:** 없음 (git 작업)

- [ ] **Step 1: `feat/lecture-voice`에서 워크트리 분기**

```bash
cd C:/Users/SSAFY/Desktop/S15P21B201
git rev-parse --verify feat/lecture-voice
git worktree add ../wt-voice-web -b feat/lecture-voice-web feat/lecture-voice
cd ../wt-voice-web
git ls-files docs/superpowers/specs/2026-09-26-lecture-two-way-voice-design.md
cd front && pnpm install
```

`git ls-files`가 설계서 경로를 출력하지 않으면 중단하고 사람에게 알린다. 이 계획 문서는 `C:/Users/SSAFY/Desktop/wt-lecture-voice/docs/superpowers/plans/`에 있다 — 이 브랜치로 복사·커밋하지 않는다(상위 브랜치에서 따로 관리). push하지 않는다.

- [ ] **Step 2: 출발점 확인**

Run: `cd C:/Users/SSAFY/Desktop/wt-voice-web/front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공, lint 경고 0. 실패하면 이 계획을 시작하지 말고 사람에게 알린다.

---

### Task 1: 발언 허용 API 호출과 오류 문구

**Files:**
- Modify: `front/apps/web/src/domains/lecture/model/types.ts`
- Modify: `front/apps/web/src/domains/lecture/api/lectureApi.ts`
- Modify: `front/apps/web/src/shared/ui/errorMessage.ts`
- Test: `front/apps/web/src/domains/lecture/api/__tests__/lectureApi.test.ts`, `front/apps/web/src/shared/ui/__tests__/errorMessage.test.ts`

**Interfaces:**
- Produces:
  - `interface SpeakingResult { userId: string; allowed: boolean }` (`model/types.ts`)
  - `lectureApi.setSpeaking(lectureId: string, userId: string, allowed: boolean): Promise<SpeakingResult>` — `PUT /lectures/{enc(lectureId)}/participants/{enc(userId)}/speaking` body `{ allowed }`
  - `errorMessage`: `LECTURE_PARTICIPANT_NOT_CONNECTED` → `'지금 연결돼 있지 않은 학생이에요.'`, `LECTURE_PARTICIPANT_NOT_FOUND` → `'수업에 참여 중인 학생이 아니에요.'`

- [ ] **Step 1: 실패하는 테스트 작성**

`lectureApi.test.ts` — `'lectureId를 경로에 넣을 때 인코딩한다'` 테스트 **앞에** 새 테스트를 넣는다:

```ts
  it('발언 허용·회수를 학생별 경로로 보낸다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    await lectures.setSpeaking('42', '7', true)
    await lectures.setSpeaking('42', '7', false)

    expect(client.put).toHaveBeenNthCalledWith(1, '/lectures/42/participants/7/speaking', { allowed: true })
    expect(client.put).toHaveBeenNthCalledWith(2, '/lectures/42/participants/7/speaking', { allowed: false })
  })
```

같은 파일의 `'lectureId를 경로에 넣을 때 인코딩한다'` 테스트 전체를 다음으로 바꾼다:

```ts
  it('lectureId와 userId를 경로에 넣을 때 인코딩한다', async () => {
    const client = fakeClient()
    const lectures = createLectureApi(client as unknown as ApiClient)
    const id = '42/../etc'
    const userId = '7/../1'
    await lectures.get(id)
    await lectures.changeStatus(id, 'OPEN')
    await lectures.issueMediaToken(id)
    await lectures.setSpeaking(id, userId, true)

    const encoded = encodeURIComponent(id)
    expect(client.get).toHaveBeenCalledWith(`/lectures/${encoded}`)
    expect(client.put).toHaveBeenCalledWith(`/lectures/${encoded}/status`, { status: 'OPEN' })
    expect(client.post).toHaveBeenCalledWith(`/lectures/${encoded}/media-tokens`)
    expect(client.put).toHaveBeenCalledWith(`/lectures/${encoded}/participants/${encodeURIComponent(userId)}/speaking`, {
      allowed: true,
    })
  })
```

`errorMessage.test.ts` — `it.each` 표의 `['NETWORK_UNAVAILABLE', …]` 줄 **뒤에** 두 줄을 넣는다:

```ts
    ['LECTURE_PARTICIPANT_NOT_CONNECTED', '지금 연결돼 있지 않은 학생이에요.'],
    ['LECTURE_PARTICIPANT_NOT_FOUND', '수업에 참여 중인 학생이 아니에요.'],
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — `setSpeaking is not a function`(lectureApi 2개), errorMessage 2개가 기본 문구를 받아 불일치

- [ ] **Step 3: 구현**

`model/types.ts` 끝의 `CreateLectureInput` 뒤에 추가:

```ts
/** 발언 허용·회수 응답 (API 명세 8.5절). 화면은 이 응답이 아니라 LiveKit 권한 변경 이벤트로 갱신한다 */
export interface SpeakingResult {
  userId: string
  allowed: boolean
}
```

`lectureApi.ts` — import 줄을 다음으로 바꾼다:

```ts
import type { CreateLectureInput, InstructorLecture, LecturePage, MediaToken, SpeakingResult } from '../model/types'
```

`issueMediaToken` 항목 **뒤에** 추가:

```ts

    /**
     * 학생 발언 허용(true)·회수(false). 멱등이며 서버는 저장하지 않는다 — LiveKit 참가자 권한이 유일한 상태다.
     * 허용·회수 모두 서버가 학생의 손(`hand`)을 내린다
     */
    setSpeaking: (lectureId: string, userId: string, allowed: boolean) =>
      client.put<SpeakingResult>(
        `/lectures/${encodeURIComponent(lectureId)}/participants/${encodeURIComponent(userId)}/speaking`,
        { allowed },
      ),
```

`errorMessage.ts` — `MESSAGES`의 `MEDIA_SERVER_UNAVAILABLE` 줄 **뒤에** 추가:

```ts
  LECTURE_PARTICIPANT_NOT_CONNECTED: '지금 연결돼 있지 않은 학생이에요.',
  LECTURE_PARTICIPANT_NOT_FOUND: '수업에 참여 중인 학생이 아니에요.',
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint`
Expected: PASS (기존 + lectureApi 1개 신규, errorMessage 2개 신규), lint 경고 0

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture/model/types.ts front/apps/web/src/domains/lecture/api front/apps/web/src/shared/ui/errorMessage.ts front/apps/web/src/shared/ui/__tests__/errorMessage.test.ts
git commit -m "feat(lecture): 강사 웹에 학생 발언 허용 API 호출을 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 학생 목록 모듈 (roster)

**Files:**
- Create: `front/apps/web/src/domains/lecture/live/roster.ts`
- Test: `front/apps/web/src/domains/lecture/live/__tests__/roster.test.ts`

**Interfaces:**
- Produces:
  - `interface RosterStudent { userId: string; name: string; handUp: boolean; speakingAllowed: boolean; micOn: boolean; isSpeaking: boolean }`
  - `interface RosterParticipant { identity: string; name?: string; attributes: Readonly<Record<string, string>>; permissions?: { canPublish: boolean }; isSpeaking: boolean; isMicrophoneEnabled: boolean }` — livekit `RemoteParticipant`가 구조적으로 만족한다
  - `userIdOf(identity: string): string | null`
  - `buildRoster(participants: Iterable<RosterParticipant>): RosterStudent[]` — `user-{숫자}`가 아닌 참가자는 뺀다. 정렬: 손든 학생 → 발언 허용 학생 → 나머지, 같은 묶음 안에서는 이름(`localeCompare(…, 'ko')`) → userId

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/live/__tests__/roster.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { buildRoster, userIdOf, type RosterParticipant } from '../roster'

function p(identity: string, name: string, patch: Partial<RosterParticipant> = {}): RosterParticipant {
  return {
    identity,
    name,
    attributes: {},
    permissions: { canPublish: false },
    isSpeaking: false,
    isMicrophoneEnabled: false,
    ...patch,
  }
}

describe('roster', () => {
  it('identity user-{id}에서 userId를 꺼낸다', () => {
    expect(userIdOf('user-42')).toBe('42')
    expect(userIdOf('user-')).toBeNull()
    expect(userIdOf('user-4a')).toBeNull()
    expect(userIdOf('instructor-1')).toBeNull()
  })

  it('참가자를 학생 카드 모델로 바꾼다', () => {
    const roster = buildRoster([
      p('user-7', '이서준', { attributes: { hand: 'up', speaking: 'none' }, isMicrophoneEnabled: true, isSpeaking: true }),
    ])
    expect(roster).toEqual([{ userId: '7', name: '이서준', handUp: true, speakingAllowed: false, micOn: true, isSpeaking: true }])
  })

  it('발언 허용은 speaking 속성이 아니라 LiveKit 권한 canPublish로 판단한다', () => {
    const roster = buildRoster([
      p('user-1', '가', { attributes: { speaking: 'allowed' } }),
      p('user-2', '나', { permissions: { canPublish: true } }),
      p('user-3', '다', { permissions: undefined }),
    ])
    expect(roster.map((s) => [s.userId, s.speakingAllowed])).toEqual([
      ['2', true],
      ['1', false],
      ['3', false],
    ])
  })

  it('이름이 비어 있으면 identity를 보여 준다', () => {
    expect(buildRoster([p('user-5', '')])[0].name).toBe('user-5')
  })

  it('user-{숫자} 형식이 아닌 참가자는 뺀다', () => {
    expect(buildRoster([p('agent-1', '녹음봇'), p('user-x', '?'), p('user-9', '정예준')]).map((s) => s.userId)).toEqual(['9'])
  })

  it('손든 학생 → 발언 허용 학생 → 나머지 순서, 같은 묶음은 이름순', () => {
    const roster = buildRoster([
      p('user-1', '홍시현'),
      p('user-2', '강민서'),
      p('user-3', '이주원', { permissions: { canPublish: true } }),
      p('user-4', '오시우', { attributes: { hand: 'up' } }),
      p('user-5', '김유진', { attributes: { hand: 'up' } }),
    ])
    expect(roster.map((s) => s.name)).toEqual(['김유진', '오시우', '이주원', '강민서', '홍시현'])
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — `../roster` 모듈이 없다

- [ ] **Step 3: 구현**

`front/apps/web/src/domains/lecture/live/roster.ts`:

```ts
/**
 * 강사 화면 학생 카드 한 장 (양방향 음성 설계 §3.3·§4). 원천은 LiveKit 원격 참가자다.
 * 이 모듈은 LiveKit을 import하지 않는다 — 필요한 필드만 구조 타입으로 받아 node 환경에서 테스트한다.
 */
export interface RosterStudent {
  userId: string
  name: string
  /** attributes.hand === 'up' */
  handUp: boolean
  /** LiveKit 권한 permissions.canPublish. attributes.speaking은 표시 전용이라 쓰지 않는다 */
  speakingAllowed: boolean
  micOn: boolean
  isSpeaking: boolean
}

/** roster가 읽는 참가자 필드. livekit-client의 RemoteParticipant가 그대로 만족한다 */
export interface RosterParticipant {
  identity: string
  name?: string
  attributes: Readonly<Record<string, string>>
  permissions?: { canPublish: boolean }
  isSpeaking: boolean
  isMicrophoneEnabled: boolean
}

const IDENTITY = /^user-(\d+)$/

/** identity `user-{userId}` → userId. 규약 밖의 참가자는 null */
export function userIdOf(identity: string): string | null {
  const match = IDENTITY.exec(identity)
  return match ? match[1] : null
}

function toStudent(participant: RosterParticipant): RosterStudent | null {
  const userId = userIdOf(participant.identity)
  if (userId === null) return null
  return {
    userId,
    name: participant.name || participant.identity,
    handUp: participant.attributes.hand === 'up',
    speakingAllowed: participant.permissions?.canPublish === true,
    micOn: participant.isMicrophoneEnabled,
    isSpeaking: participant.isSpeaking,
  }
}

/** 손든 학생(0) → 발언 허용 학생(1) → 나머지(2) */
function group(student: RosterStudent): number {
  if (student.handUp) return 0
  if (student.speakingAllowed) return 1
  return 2
}

export function buildRoster(participants: Iterable<RosterParticipant>): RosterStudent[] {
  const students: RosterStudent[] = []
  for (const participant of participants) {
    const student = toStudent(participant)
    if (student) students.push(student)
  }
  return students.sort(
    (a, b) => group(a) - group(b) || a.name.localeCompare(b.name, 'ko') || a.userId.localeCompare(b.userId),
  )
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint`
Expected: PASS (+6), lint 경고 0

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture/live/roster.ts front/apps/web/src/domains/lecture/live/__tests__/roster.test.ts
git commit -m "feat(lecture): LiveKit 참가자를 강사 화면 학생 목록으로 바꾸는 모듈을 추가한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 강사 음성 방 — 학생 목록 구독과 학생 음성 재생

**Files:**
- Modify: `front/apps/web/src/domains/lecture/live/instructorRoom.ts` (전체 교체)
- Test: `front/apps/web/src/domains/lecture/live/__tests__/instructorRoom.test.ts` (전체 교체 — 기존 5개 테스트는 그대로 남는다)

**Interfaces:**
- Consumes: `buildRoster`, `RosterStudent` (Task 2)
- Produces:
  - `interface RoomListeners { onRoster?: (students: RosterStudent[]) => void; onAudioPlayback?: (playing: boolean) => void }`
  - `connectInstructorRoom(token: MediaToken, onState: (state: RoomState) => void, listeners?: RoomListeners): Promise<InstructorRoom>` — 두 인자 호출은 그대로 동작
  - `InstructorRoom` 추가 메서드: `roster(): RosterStudent[]`, `canPlaybackAudio(): boolean`, `startAudio(): Promise<boolean>`(throw하지 않는다 — 풀렸으면 true)
  - 동작: 접속 직후 `onRoster(스냅샷)`, 참가자 이벤트 10종마다 `onRoster(새 목록)`, 해제 시 `onRoster([])`. 원격 **오디오** 트랙 구독 시 `attach()`, 구독 해제·`disconnect()`·`Disconnected` 시 `detach()`. 마이크를 켠 직후 `startAudio()`를 한 번 시도한다(“수업 시작” 클릭 흐름 안). 기존 실패·정리 규칙(연결 실패 → `disconnected` 한 번, 마이크 실패 → 방 정리 후 `disconnected` 한 번)은 바뀌지 않는다

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/web/src/domains/lecture/live/__tests__/instructorRoom.test.ts` 전체:

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { connectInstructorRoom } from '../instructorRoom'
import type { RosterStudent } from '../roster'

type Listener = (...args: unknown[]) => void

interface FakeParticipant {
  identity: string
  name?: string
  attributes: Record<string, string>
  permissions?: { canPublish: boolean }
  isSpeaking: boolean
  isMicrophoneEnabled: boolean
}

function participant(userId: string, name: string): FakeParticipant {
  return {
    identity: `user-${userId}`,
    name,
    attributes: {},
    permissions: { canPublish: false },
    isSpeaking: false,
    isMicrophoneEnabled: false,
  }
}

function fakeTrack(kind: 'audio' | 'video') {
  return { kind, attach: vi.fn(), detach: vi.fn() }
}

const rooms: FakeRoom[] = []
/** 다음에 만들어질 방에 이미 들어와 있는 학생 */
let present: FakeParticipant[] = []

class FakeRoom {
  listeners = new Map<string, Listener[]>()
  remoteParticipants = new Map<string, FakeParticipant>(present.map((p) => [p.identity, p]))
  canPlaybackAudio = true
  connect = vi.fn(async () => undefined)
  disconnect = vi.fn(async () => {
    this.emit('disconnected')
  })
  startAudio = vi.fn(async (): Promise<void> => {})
  localParticipant = { setMicrophoneEnabled: vi.fn(async () => undefined) }
  constructor() {
    rooms.push(this)
  }
  on(event: string, listener: Listener) {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener])
    return this
  }
  emit(event: string, ...args: unknown[]) {
    ;(this.listeners.get(event) ?? []).forEach((l) => l(...args))
  }
  join(p: FakeParticipant) {
    this.remoteParticipants.set(p.identity, p)
    this.emit('participantConnected', p)
  }
  leave(p: FakeParticipant) {
    // LiveKit도 목록에서 먼저 지운 뒤 이벤트를 낸다
    this.remoteParticipants.delete(p.identity)
    this.emit('participantDisconnected', p)
  }
}

vi.mock('livekit-client', () => ({
  Room: vi.fn().mockImplementation(() => new FakeRoom()),
  RoomEvent: {
    Reconnecting: 'reconnecting',
    Reconnected: 'reconnected',
    Disconnected: 'disconnected',
    ParticipantConnected: 'participantConnected',
    ParticipantDisconnected: 'participantDisconnected',
    ParticipantAttributesChanged: 'participantAttributesChanged',
    ParticipantPermissionsChanged: 'participantPermissionsChanged',
    ParticipantNameChanged: 'participantNameChanged',
    TrackPublished: 'trackPublished',
    TrackUnpublished: 'trackUnpublished',
    TrackMuted: 'trackMuted',
    TrackUnmuted: 'trackUnmuted',
    ActiveSpeakersChanged: 'activeSpeakersChanged',
    TrackSubscribed: 'trackSubscribed',
    TrackUnsubscribed: 'trackUnsubscribed',
    AudioPlaybackStatusChanged: 'audioPlaybackChanged',
  },
  Track: { Kind: { Audio: 'audio', Video: 'video' } },
}))

const token = { serverUrl: 'wss://openvidu.i-eum.site', token: 't', expiresAt: 'x' }

beforeEach(() => {
  rooms.length = 0
  present = []
})

describe('instructorRoom', () => {
  it('접속하고 마이크를 켠다', async () => {
    const states: string[] = []
    const room = await connectInstructorRoom(token, (s) => states.push(s))

    expect(rooms[0].connect).toHaveBeenCalledWith('wss://openvidu.i-eum.site', 't')
    expect(rooms[0].localParticipant.setMicrophoneEnabled).toHaveBeenCalledWith(true)
    expect(room.micEnabled()).toBe(true)
    expect(states).toEqual(['connecting', 'connected'])
  })

  it('마이크를 끄고 켠다', async () => {
    const room = await connectInstructorRoom(token, () => {})

    await room.setMicEnabled(false)

    expect(rooms[0].localParticipant.setMicrophoneEnabled).toHaveBeenLastCalledWith(false)
    expect(room.micEnabled()).toBe(false)
  })

  it('재연결과 해제 상태를 알린다', async () => {
    const states: string[] = []
    await connectInstructorRoom(token, (s) => states.push(s))

    rooms[0].emit('reconnecting')
    rooms[0].emit('reconnected')
    rooms[0].emit('disconnected')

    expect(states).toEqual(['connecting', 'connected', 'reconnecting', 'connected', 'disconnected'])
  })

  it('접속이 실패하면 오류를 던지고 해제 상태를 알린다', async () => {
    const states: string[] = []
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.connect.mockRejectedValue(new Error('ws failed'))
      return room as never
    })

    await expect(connectInstructorRoom(token, (s) => states.push(s))).rejects.toThrow('ws failed')
    expect(states).toEqual(['connecting', 'disconnected'])
  })

  it('마이크를 켜지 못하면 연결을 정리하고 해제 상태를 한 번만 알린다', async () => {
    const states: string[] = []
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.localParticipant.setMicrophoneEnabled.mockRejectedValue(new Error('mic denied'))
      return room as never
    })

    await expect(connectInstructorRoom(token, (s) => states.push(s))).rejects.toThrow('mic denied')

    expect(rooms[0].disconnect).toHaveBeenCalled()
    expect(rooms[0].startAudio).not.toHaveBeenCalled()
    expect(states).toEqual(['connecting', 'connected', 'disconnected'])
  })

  it('접속하면 이미 들어와 있는 학생 목록을 알리고 스냅샷으로도 준다', async () => {
    present = [participant('7', '이서준'), participant('3', '김도윤')]
    const rosters: RosterStudent[][] = []

    const room = await connectInstructorRoom(token, () => {}, { onRoster: (r) => rosters.push(r) })

    expect(rosters.map((r) => r.map((s) => s.userId))).toEqual([['3', '7']])
    expect(room.roster().map((s) => s.name)).toEqual(['김도윤', '이서준'])
  })

  it('입장·손들기·발언 권한·마이크·말하는 중·퇴장 때마다 새 목록을 알린다', async () => {
    const rosters: RosterStudent[][] = []
    const last = () => rosters[rosters.length - 1]
    await connectInstructorRoom(token, () => {}, { onRoster: (r) => rosters.push(r) })
    const room = rooms[0]
    const a = participant('1', '박지호')
    const b = participant('2', '강민서')

    room.join(a)
    room.join(b)
    expect(last().map((s) => s.name)).toEqual(['강민서', '박지호'])

    a.attributes = { hand: 'up' }
    room.emit('participantAttributesChanged', { hand: 'up' }, a)
    expect(last().map((s) => [s.name, s.handUp])).toEqual([
      ['박지호', true],
      ['강민서', false],
    ])

    a.attributes = { hand: 'down', speaking: 'allowed' }
    a.permissions = { canPublish: true }
    room.emit('participantPermissionsChanged', { canPublish: false }, a)
    expect(last()[0]).toMatchObject({ userId: '1', handUp: false, speakingAllowed: true })

    a.isMicrophoneEnabled = true
    room.emit('trackPublished', {}, a)
    a.isSpeaking = true
    room.emit('activeSpeakersChanged', [a])
    expect(last()[0]).toMatchObject({ userId: '1', micOn: true, isSpeaking: true })

    room.leave(a)
    expect(last().map((s) => s.userId)).toEqual(['2'])
  })

  it('학생 오디오 트랙만 재생 요소에 붙이고, 구독이 끝나면 뗀다', async () => {
    await connectInstructorRoom(token, () => {})
    const a = participant('1', '박지호')
    const voice = fakeTrack('audio')
    const video = fakeTrack('video')

    rooms[0].emit('trackSubscribed', voice, {}, a)
    rooms[0].emit('trackSubscribed', video, {}, a)
    expect(voice.attach).toHaveBeenCalledTimes(1)
    expect(video.attach).not.toHaveBeenCalled()

    rooms[0].emit('trackUnsubscribed', voice, {}, a)
    rooms[0].emit('trackUnsubscribed', video, {}, a)
    expect(voice.detach).toHaveBeenCalledTimes(1)
    expect(video.detach).not.toHaveBeenCalled()
  })

  it('연결을 끊으면 붙인 소리를 모두 한 번씩 떼고 빈 목록을 알린다', async () => {
    present = [participant('1', '박지호')]
    const rosters: RosterStudent[][] = []
    const room = await connectInstructorRoom(token, () => {}, { onRoster: (r) => rosters.push(r) })
    const voice = fakeTrack('audio')
    rooms[0].emit('trackSubscribed', voice, {}, present[0])

    await room.disconnect()

    expect(rooms[0].disconnect).toHaveBeenCalled()
    expect(voice.detach).toHaveBeenCalledTimes(1)
    expect(rosters[rosters.length - 1]).toEqual([])
  })

  it('마이크를 켠 뒤 소리 재생을 풀어 보고, 막혔으면 다시 시도할 수 있다', async () => {
    const { Room } = await import('livekit-client')
    vi.mocked(Room).mockImplementationOnce(() => {
      const room = new FakeRoom()
      room.startAudio.mockImplementation(async () => {
        room.canPlaybackAudio = false
        throw new DOMException('blocked', 'NotAllowedError')
      })
      return room as never
    })

    const room = await connectInstructorRoom(token, () => {})
    expect(rooms[0].startAudio).toHaveBeenCalledTimes(1)
    expect(room.canPlaybackAudio()).toBe(false)

    rooms[0].startAudio.mockImplementation(async () => {
      rooms[0].canPlaybackAudio = true
    })
    await expect(room.startAudio()).resolves.toBe(true)
    expect(room.canPlaybackAudio()).toBe(true)
  })

  it('소리 재생 상태 변화를 알린다', async () => {
    const playback: boolean[] = []
    await connectInstructorRoom(token, () => {}, { onAudioPlayback: (playing) => playback.push(playing) })

    rooms[0].emit('audioPlaybackChanged', false)
    rooms[0].emit('audioPlaybackChanged', true)

    expect(playback).toEqual([false, true])
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/web test`
Expected: FAIL — 기존 5개는 통과, 새 6개가 실패(`room.roster is not a function`, `attach` 미호출, `startAudio` 미호출 등). `tsc`가 아니라 vitest 결과로 확인한다

- [ ] **Step 3: 구현**

`front/apps/web/src/domains/lecture/live/instructorRoom.ts` 전체:

```ts
import { Room, RoomEvent, Track, type RemoteTrack } from 'livekit-client'
import type { MediaToken } from '../model/types'
import { buildRoster, type RosterStudent } from './roster'

export type RoomState = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

export interface RoomListeners {
  /** 학생 목록이 바뀔 때마다 정렬된 새 목록. 방이 끊기면 빈 목록 */
  onRoster?: (students: RosterStudent[]) => void
  /** 브라우저가 학생 소리 재생을 막으면 false, 풀리면 true */
  onAudioPlayback?: (playing: boolean) => void
}

export interface InstructorRoom {
  setMicEnabled(enabled: boolean): Promise<void>
  micEnabled(): boolean
  /** 지금 방에 있는 학생 목록 스냅샷 (정렬됨) */
  roster(): RosterStudent[]
  /** false면 브라우저 자동 재생 제한으로 학생 소리가 나지 않는다 */
  canPlaybackAudio(): boolean
  /** 막힌 소리 재생을 푼다. 사용자 클릭 안에서 부른다. throw하지 않고, 풀렸으면 true */
  startAudio(): Promise<boolean>
  disconnect(): Promise<void>
}

/**
 * 강사 음성 방 (API 명세 8.5절, 양방향 음성 설계 §4). 강사는 마이크를 송출하고,
 * 강사가 발언을 허용한 학생의 목소리를 듣는다. 학생 목록은 LiveKit 원격 참가자에서 만든다.
 * 브라우저 마이크 권한은 HTTPS 또는 localhost에서만 열린다.
 */
export async function connectInstructorRoom(
  token: MediaToken,
  onState: (state: RoomState) => void,
  listeners: RoomListeners = {},
): Promise<InstructorRoom> {
  const room = new Room()
  let mic = false
  let lastState: RoomState | undefined
  const attached = new Set<RemoteTrack>()

  const emit = (state: RoomState) => {
    lastState = state
    onState(state)
  }

  const snapshot = () => buildRoster(room.remoteParticipants.values())
  const publishRoster = () => listeners.onRoster?.(snapshot())

  const detachAll = () => {
    attached.forEach((track) => track.detach())
    attached.clear()
  }

  const startAudio = async () => {
    try {
      await room.startAudio()
    } catch {
      // 여전히 막혀 있다 — 결과는 canPlaybackAudio와 AudioPlaybackStatusChanged로 알린다
    }
    return room.canPlaybackAudio
  }

  room.on(RoomEvent.Reconnecting, () => emit('reconnecting'))
  room.on(RoomEvent.Reconnected, () => emit('connected'))
  room.on(RoomEvent.Disconnected, () => {
    detachAll()
    emit('disconnected')
    listeners.onRoster?.([])
  })

  // 입장·퇴장, 손들기(attributes), 발언 권한(permissions), 이름,
  // 마이크(첫 켜기 = 게시, 끄기/켜기 = 음소거, 발언 회수 = 서버가 내림), 말하는 중
  room
    .on(RoomEvent.ParticipantConnected, publishRoster)
    .on(RoomEvent.ParticipantDisconnected, publishRoster)
    .on(RoomEvent.ParticipantAttributesChanged, publishRoster)
    .on(RoomEvent.ParticipantPermissionsChanged, publishRoster)
    .on(RoomEvent.ParticipantNameChanged, publishRoster)
    .on(RoomEvent.TrackPublished, publishRoster)
    .on(RoomEvent.TrackUnpublished, publishRoster)
    .on(RoomEvent.TrackMuted, publishRoster)
    .on(RoomEvent.TrackUnmuted, publishRoster)
    .on(RoomEvent.ActiveSpeakersChanged, publishRoster)

  // 학생 목소리 재생. attach()가 audio 요소를 만들고 play()까지 부른다 — 문서에 붙이지 않아도 재생된다
  room.on(RoomEvent.TrackSubscribed, (track) => {
    if (track.kind !== Track.Kind.Audio) return
    track.attach()
    attached.add(track)
  })
  room.on(RoomEvent.TrackUnsubscribed, (track) => {
    if (attached.delete(track)) track.detach()
  })
  room.on(RoomEvent.AudioPlaybackStatusChanged, (playing) => listeners.onAudioPlayback?.(playing))

  emit('connecting')
  try {
    await room.connect(token.serverUrl, token.token)
  } catch (error) {
    emit('disconnected')
    throw error
  }
  emit('connected')
  publishRoster()

  try {
    await room.localParticipant.setMicrophoneEnabled(true)
  } catch (error) {
    try {
      await room.disconnect()
    } catch {
      // 연결 정리 중 발생한 오류는 원래 마이크 오류를 가리므로 무시한다
    }
    if (lastState !== 'disconnected') {
      emit('disconnected')
    }
    throw error
  }
  mic = true
  // "수업 시작" 클릭에서 이어진 흐름이면 여기서 재생이 풀린다. 새로고침 자동 접속처럼 제스처가 없으면
  // 막힐 수 있고, 그때는 화면이 "학생 소리 켜기" 버튼으로 다시 부른다
  await startAudio()

  return {
    async setMicEnabled(enabled) {
      await room.localParticipant.setMicrophoneEnabled(enabled)
      mic = enabled
    },
    micEnabled: () => mic,
    roster: snapshot,
    canPlaybackAudio: () => room.canPlaybackAudio,
    startAudio,
    async disconnect() {
      detachAll()
      await room.disconnect()
    },
  }
}
```

참고: `room.startAudio()`는 내부에서 AudioContext 재개를 200ms로 제한해 기다리므로 접속 흐름을 오래 막지 않는다(2.22.3 `acquireAudioContext` 확인).

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: PASS (instructorRoom 11개), lint 경고 0, build 성공(페이지는 아직 두 인자로 호출하므로 그대로 컴파일된다)

- [ ] **Step 5: Commit**

```bash
git add front/apps/web/src/domains/lecture/live
git commit -m "feat(lecture): 강사 음성 방에서 학생 목록을 구독하고 학생 음성을 재생한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 실시간 수업 화면 — 실제 학생 그리드와 발언 허용

**Files:**
- Modify: `front/apps/web/src/pages/LiveLecturePage.tsx`
- Modify: `front/apps/web/src/domains/lecture/mock/liveMock.ts` (머리 주석만)

**Interfaces:**
- Consumes: `lectureApi.setSpeaking` (Task 1), `RosterStudent` (Task 2), `connectInstructorRoom(token, onState, { onRoster, onAudioPlayback })`, `room.roster()`, `room.canPlaybackAudio()`, `room.startAudio()` (Task 3)
- Produces: 화면 동작
  - 학생 그리드 = `students`(실제 참가자, 정렬됨). 없으면 "아직 입장한 학생이 없어요"
  - 카드: 이름, ✋(손 듦), "발언 허용" 배지, "🎙 마이크 켜짐" / "마이크 꺼짐", 말하는 중이면 민트 테두리 + "말하는 중", 점선 "예시" 칸(목 답안·장치 점), 수업 중·연결됨일 때만 "발언 허용" / "발언 끄기" 버튼
  - 버튼은 `run()`으로 `setSpeaking` 호출, 누른 학생 카드만 "처리 중…", 요청 중에는 모든 발언 버튼과 기존 버튼이 `busy`로 잠긴다
  - 자동 재생이 막히면 학생 그리드 제목 옆에 "학생 소리 켜기" 버튼
  - 새 방 콜백(`onRoster`·`onAudioPlayback`)도 `seq === joinSeqRef.current`일 때만 상태에 반영한다

이 페이지에는 테스트가 없다(vitest는 node 환경, 화면은 build·lint와 Task 5 E2E로 확인 — `vitest.config.ts` 주석의 기존 규칙). 아래 편집은 모두 "찾을 코드 → 바꿀 코드"로, 찾을 코드는 현재 파일에 정확히 한 번 나온다.

- [ ] **Step 1: import**

찾을 코드:

```tsx
import { connectInstructorRoom, type InstructorRoom, type RoomState } from '../domains/lecture/live/instructorRoom'
```

바꿀 코드:

```tsx
import { connectInstructorRoom, type InstructorRoom, type RoomState } from '../domains/lecture/live/instructorRoom'
import type { RosterStudent } from '../domains/lecture/live/roster'
```

- [ ] **Step 2: 범례에서 "미입장" 제거 (입장 여부는 이제 실제 목록이 보여 준다)**

찾을 코드:

```tsx
  { label: '미착수', swatch: { background: c.bg, border: `1px solid ${c.borderSoft}` } },
  { label: '미입장', swatch: { border: `1.5px solid ${c.borderStrong}` } },
]
```

바꿀 코드:

```tsx
  { label: '미착수', swatch: { background: c.bg, border: `1px solid ${c.borderSoft}` } },
]
```

- [ ] **Step 3: `StudentCard` 교체**

찾을 코드: `function StudentCard({ student }: { student: MockStudent }) {` 부터 그 함수의 닫는 `}`(바로 다음 `function SetItem` 앞)까지 전체.

바꿀 코드:

```tsx
/** 답안 상태는 아직 목이다(문제 출제·답안 API는 범위 밖). 미입장 예시는 실제 명단과 모순되므로 뺀다 */
const MOCK_ANSWERS = mockStudents.filter((s) => s.state !== 'absent')

/** 같은 학생에게는 정렬 순서가 바뀌어도 같은 예시 답안을 보여 준다 */
function mockAnswerFor(userId: string): MockStudent {
  return MOCK_ANSWERS[Number(userId) % MOCK_ANSWERS.length]
}

interface StudentCardProps {
  student: RosterStudent
  /** 수업 중이고 음성 방에 연결돼 있을 때만 발언을 바꿀 수 있다 */
  canControl: boolean
  /** 이 학생의 발언 요청이 진행 중 */
  pending: boolean
  disabled: boolean
  onToggleSpeaking: () => void
}

function StudentCard({ student, canControl, pending, disabled, onToggleSpeaking }: StudentCardProps) {
  const answer = mockAnswerFor(student.userId)
  const look = STUDENT_LOOK[answer.state]
  const action = student.speakingAllowed ? '발언 끄기' : '발언 허용'
  return (
    <li
      style={{
        borderRadius: theme.radius.control,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        background: c.surface,
        border: `1.5px solid ${student.isSpeaking ? c.primary : c.border}`,
        boxShadow: student.isSpeaking ? `0 0 0 3px ${c.primarySoft}` : 'none',
      }}
    >
      {/* 실제: 이름·손들기·발언 허용·마이크·말하는 중 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 14, fontWeight: 800, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {student.name}
        </span>
        {student.handUp && (
          <span role="img" aria-label="손 들었어요" style={{ fontSize: 18, lineHeight: 1, flexShrink: 0 }}>
            ✋
          </span>
        )}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, fontSize: 11.5, fontWeight: 700 }}>
        {student.speakingAllowed && (
          <span style={{ padding: '2px 8px', borderRadius: theme.radius.pill, background: c.primarySoft, color: c.primaryDark }}>
            발언 허용
          </span>
        )}
        <span style={{ color: student.micOn ? c.primaryDark : c.textMuted }}>{student.micOn ? '🎙 마이크 켜짐' : '마이크 꺼짐'}</span>
        {student.isSpeaking && <span style={{ color: c.primaryDark }}>말하는 중</span>}
      </div>
      {/* 목: 답안 상태 예시 — 점선 칸으로 실제 정보와 구분한다 */}
      <div
        style={{
          ...look.card,
          border: `1px dashed ${c.borderStrong}`,
          borderRadius: theme.radius.small,
          padding: '6px 8px',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <span style={{ fontSize: 10, fontWeight: 700, color: c.textMuted }}>예시</span>
        {answer.glyph && <span style={{ fontSize: 18, lineHeight: 1, color: look.accent }}>{answer.glyph}</span>}
        <span style={{ fontSize: 11.5, fontWeight: 700, color: look.accent, fontFamily: answer.state === 'correct' ? mono : undefined }}>
          {answer.detail}
        </span>
        <span
          role="img"
          aria-label={answer.deviceConnected ? '장치 연결됨(예시)' : '장치 끊김(예시)'}
          style={{
            marginLeft: 'auto',
            width: 9,
            height: 9,
            borderRadius: '50%',
            flexShrink: 0,
            background: answer.deviceConnected ? c.primaryDark : c.live,
          }}
        />
      </div>
      {canControl && (
        <button
          type="button"
          disabled={disabled}
          onClick={onToggleSpeaking}
          aria-label={`${student.name} ${action}`}
          style={{
            ...buttonStyle(student.speakingAllowed ? 'dangerOutline' : 'mintOutline', 'sm'),
            padding: '6px 10px',
            fontSize: 12,
            ...(disabled ? dimmed : {}),
          }}
        >
          {pending ? '처리 중…' : action}
        </button>
      )}
    </li>
  )
}
```

- [ ] **Step 4: 상태 추가**

찾을 코드:

```tsx
  const [endFailed, setEndFailed] = useState(false)
```

바꿀 코드:

```tsx
  const [endFailed, setEndFailed] = useState(false)
  const [students, setStudents] = useState<RosterStudent[]>([])
  const [audioBlocked, setAudioBlocked] = useState(false)
  const [pendingUserId, setPendingUserId] = useState<string | null>(null)
```

- [ ] **Step 5: `joinVoice` — 새 콜백도 세대 번호로 거른다**

찾을 코드:

```tsx
      await releaseRoom()
      if (seq !== joinSeqRef.current) return // 더 나중 호출이나 언마운트에 밀려났다
      const token = await lectureApi.issueMediaToken(lectureId)
      if (seq !== joinSeqRef.current) return
      const room = await connectInstructorRoom(token, (state) => {
        // 이 방이 더 이상 최신 접속이 아니면(다른 접속으로 대체됐거나 언마운트됐으면) 상태를 덮어쓰지 않는다
        if (seq === joinSeqRef.current) setRoomState(state)
      })
```

바꿀 코드:

```tsx
      await releaseRoom()
      if (seq !== joinSeqRef.current) return // 더 나중 호출이나 언마운트에 밀려났다
      // 이전 방의 학생 목록·재생 상태는 그 방의 콜백이 이미 세대 번호로 막혀 있어 여기서 비운다
      setStudents([])
      setAudioBlocked(false)
      const token = await lectureApi.issueMediaToken(lectureId)
      if (seq !== joinSeqRef.current) return
      // 이 방이 더 이상 최신 접속이 아니면(다른 접속으로 대체됐거나 언마운트됐으면) 어떤 콜백도 화면을 덮어쓰지 않는다
      const room = await connectInstructorRoom(
        token,
        (state) => {
          if (seq === joinSeqRef.current) setRoomState(state)
        },
        {
          onRoster: (next) => {
            if (seq === joinSeqRef.current) setStudents(next)
          },
          onAudioPlayback: (playing) => {
            if (seq === joinSeqRef.current) setAudioBlocked(!playing)
          },
        },
      )
```

같은 함수에서 찾을 코드:

```tsx
      roomRef.current = room
      setMic(true)
```

바꿀 코드:

```tsx
      roomRef.current = room
      setMic(true)
      setStudents(room.roster())
      setAudioBlocked(!room.canPlaybackAudio())
```

- [ ] **Step 6: 종료 503 경로에서 목록·재생 상태도 비운다**

찾을 코드:

```tsx
          setMic(false)
          setRoomState('disconnected')
```

바꿀 코드:

```tsx
          setMic(false)
          setRoomState('disconnected')
          setStudents([])
          setAudioBlocked(false)
```

- [ ] **Step 7: 발언 허용·끄기와 학생 소리 켜기 핸들러**

찾을 코드:

```tsx
      await room.setMicEnabled(!mic)
      setMic(room.micEnabled())
    })
```

바꿀 코드:

```tsx
      await room.setMicEnabled(!mic)
      setMic(room.micEnabled())
    })

  // 화면은 응답이 아니라 LiveKit 권한·속성 변경 이벤트(onRoster)로 갱신된다 — 권한이 유일한 상태다
  const toggleSpeaking = (student: RosterStudent) =>
    run(async () => {
      setPendingUserId(student.userId)
      try {
        await lectureApi.setSpeaking(lectureId, student.userId, !student.speakingAllowed)
      } finally {
        setPendingUserId(null)
      }
    })

  // 사용자 클릭 안에서 불러야 브라우저가 재생을 허락한다
  const enableAudio = () =>
    run(async () => {
      const room = roomRef.current
      if (!room) return
      const playing = await room.startAudio()
      if (roomRef.current === room) setAudioBlocked(!playing)
    })
```

- [ ] **Step 8: 렌더 값**

찾을 코드:

```tsx
  const open = lecture.status === 'OPEN'
```

바꿀 코드:

```tsx
  const open = lecture.status === 'OPEN'
  const canControl = open && roomState === 'connected'
```

- [ ] **Step 9: 학생 그리드 섹션 교체**

찾을 코드: `{/* 목: 학생 진행 상태 — 학습자는 숨김 참가자라 실제 입장 여부도 알 수 없다 */}` 줄부터 바로 뒤 `<section …>`의 닫는 `</section>`(그 다음 줄이 `</main>`)까지 전체.

바꿀 코드:

```tsx
          {/* 실제: 입장한 학생(LiveKit 참가자)·손들기·발언 허용·마이크 / 목: 카드 안 점선 "예시" 칸의 답안 상태 */}
          <section style={{ ...cardStyle, flex: 1, minHeight: 0, padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800 }}>학생 · {students.length}명</h2>
                {canControl && audioBlocked && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={enableAudio}
                    style={{
                      ...buttonStyle('primary', 'sm'),
                      padding: '6px 14px',
                      fontSize: 12.5,
                      boxShadow: `0 2px 0 ${c.primaryStrong}`,
                      ...(busy ? dimmed : {}),
                    }}
                  >
                    학생 소리 켜기
                  </button>
                )}
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 14, fontSize: 12, color: c.textSub }}>
                <span style={{ fontWeight: 700 }}>답안 상태(예시)</span>
                {LEGEND.map((item) => (
                  <span key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                    <span style={{ width: 9, height: 9, borderRadius: 2, ...item.swatch }} />
                    {item.label}
                  </span>
                ))}
                <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 9, height: 9, borderRadius: '50%', background: c.primaryDark }} />
                  점 = 장치 연결 여부
                </span>
              </div>
            </div>
            {students.length === 0 ? (
              <p style={{ margin: 0, fontSize: 14, color: c.textSub }}>아직 입장한 학생이 없어요</p>
            ) : (
              <ul
                style={{
                  listStyle: 'none',
                  margin: 0,
                  padding: 0,
                  flex: 1,
                  minHeight: 0,
                  overflowY: 'auto',
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(168px, 1fr))',
                  alignContent: 'start',
                  gap: 10,
                }}
              >
                {students.map((student) => (
                  <StudentCard
                    key={student.userId}
                    student={student}
                    canControl={canControl}
                    pending={pendingUserId === student.userId}
                    disabled={busy || joining}
                    onToggleSpeaking={() => void toggleSpeaking(student)}
                  />
                ))}
              </ul>
            )}
          </section>
```

- [ ] **Step 10: 내 마이크 카드 안내 문구**

찾을 코드:

```tsx
            <div style={{ fontSize: 11.5, color: c.darkTextSub }}>강사 → 학생 단방향 음성 · 녹음·저장 없음</div>
```

바꿀 코드:

```tsx
            <div style={{ fontSize: 11.5, color: c.darkTextSub }}>발언을 허용한 학생만 말할 수 있어요 · 녹음·저장 없음</div>
```

- [ ] **Step 11: 목 데이터 머리 주석**

`front/apps/web/src/domains/lecture/mock/liveMock.ts`에서 찾을 코드:

```ts
 * 03 실시간 수업 화면의 목 데이터. 문제 출제·답안·학생 진행 상태 API는 MVP 범위 밖이다.
 * 학습자는 음성 방에서 숨김 참가자라 실제 입장 여부도 여기서는 알 수 없다.
```

바꿀 코드:

```ts
 * 03 실시간 수업 화면의 목 데이터. 문제 출제·답안·학생 진행 상태 API는 MVP 범위 밖이다.
 * 학생 명단은 실제 LiveKit 참가자로 바뀌었고, mockStudents는 학생 카드 안 "예시" 답안 칸에만 쓴다.
```

- [ ] **Step 12: 남은 참조 확인**

Run: `cd front/apps/web && grep -n "mockStudents\|숨김\|StudentCard\|MockStudent\b" src/pages/LiveLecturePage.tsx`
Expected: `mockStudents`는 import와 `MOCK_ANSWERS` 두 곳, "숨김"은 0건, `StudentCard`는 정의·사용 각 1곳, `MockStudent`는 import와 `mockAnswerFor` 반환 타입

- [ ] **Step 13: 확인**

Run: `cd front && pnpm --filter @ieum/web test && pnpm --filter @ieum/web lint && pnpm --filter @ieum/web build`
Expected: 모두 성공, lint 경고 0(`react-hooks/exhaustive-deps` 경고가 나오면 경고대로 의존성 배열을 맞추되 동작은 바꾸지 않는다 — `joinVoice`의 의존성은 여전히 `[lectureId, releaseRoom]`이어야 한다: 새로 쓰는 `setStudents`·`setAudioBlocked`는 setter라 의존성이 아니다)

- [ ] **Step 14: Commit**

```bash
git add front/apps/web/src/pages/LiveLecturePage.tsx front/apps/web/src/domains/lecture/mock/liveMock.ts
git commit -m "feat(lecture): 실시간 수업 화면에 학생 목록과 발언 허용을 연결한다" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 브라우저 E2E (사람과 함께)

**Files:** 없음 (확인 절차)

선행: `feat/lecture-voice-api`(학습자 토큰 `hidden` 제거·`canUpdateOwnMetadata`, 발언 API)와 `feat/lecture-voice-app`(손들기·마이크)이 `feat/lecture-voice`에 병합돼 있어야 한다. 웹만 먼저 볼 때는 3·4번까지 meet.livekit.io로 확인할 수 있다(손들기는 앱이 있어야 한다).

- [ ] **Step 1: 실행**

1. backend: 병합된 `feat/lecture-voice` 워크트리(`C:/Users/SSAFY/Desktop/wt-lecture-voice`)에서 `docker compose up -d --build backend`
2. 웹: `C:/Users/SSAFY/Desktop/wt-voice-web/front`에서 `pnpm dev:web` → `http://localhost:5173` (마이크 권한은 localhost에서 열린다). 이 프로세스의 PID를 기록해 둔다(`netstat -ano | findstr :5173`)
3. 학습자 A: 학습자 앱(`feat/lecture-voice-app` 빌드, 실기기). 학습자 B: 두 번째 기기의 앱, 또는 사람이 학습자 B 계정의 access token으로 `POST /api/v1/lectures/{id}/media-tokens`를 호출해 받은 serverUrl·token을 https://meet.livekit.io Custom 탭에 넣는다(토큰 입력은 사람이 한다)

- [ ] **Step 2: 시나리오**

1. 강사 로그인 → 수업 시작 → 내 마이크 "송출 중". 학생이 없으면 그리드에 "아직 입장한 학생이 없어요"
2. A 앱에서 코드 입장 → 그리드에 A 카드(이름 = username), "마이크 꺼짐", 버튼 "발언 허용", 점선 "예시" 칸. 제목이 "학생 · 1명"
3. B 입장 → 카드 2장, 이름순. B가 나가면 B 카드가 사라진다
4. A 손들기 → A 카드에 ✋, 목록 맨 위로
5. "발언 허용" → 잠깐 "처리 중…" → A 카드 "발언 허용" 배지·✋ 사라짐·버튼 "발언 끄기", A 앱 안내 낭독
6. A 마이크 켜기 → "🎙 마이크 켜짐", A가 말하면 민트 테두리 + "말하는 중", **강사 브라우저와 B에서 A 목소리가 들린다**. A 끄기/켜기가 카드에 바로 반영된다
7. "발언 끄기" → A 카드 배지·🎙 사라짐, A 앱 마이크가 꺼지고 다시 켤 수 없다
8. A가 앱을 닫은 직후(그리드에서 사라지기 전) "발언 허용"을 누르면 "지금 연결돼 있지 않은 학생이에요." 또는 "수업에 참여 중인 학생이 아니에요."가 뜬다(경합이라 재현되지 않으면 건너뛴다)
9. 강사 페이지를 새로고침 → 자동 재접속 후 학생 목록·발언 상태가 LiveKit 상태 그대로 다시 보인다. 브라우저가 재생을 막으면 "학생 소리 켜기" 버튼이 보이고, 누르면 사라지며 A 목소리가 들린다
10. "음성 다시 연결"(서버 쪽에서 강사 연결을 끊었을 때) → 이전 방 목록이 섞이지 않고 새 방 목록으로 다시 채워진다
11. "수업 종료" → 목록으로 이동, 학생 앱 연결이 끊긴다

Expected: 1~7, 9, 11 통과(8·10은 재현될 때). 결과는 MR 본문에 기록한다.

- [ ] **Step 3: 정리**

웹 dev 서버는 기록한 PID로만 멈춘다: `taskkill //PID <pid> //F`. backend 컨테이너는 `docker compose stop backend`. 이름 단위 프로세스 종료는 하지 않는다.

---

## 완료 확인 (MR 전)

- [ ] `cd front && pnpm --filter @ieum/web test` — 기존 테스트 + lectureApi 1 + errorMessage 2 + roster 6 + instructorRoom 6 신규가 모두 PASS임을 확인했다
- [ ] `cd front && pnpm --filter @ieum/web lint` — 오류·경고 0임을 확인했다
- [ ] `cd front && pnpm --filter @ieum/web build` — tsc 타입체크 포함 성공을 확인했다
- [ ] `docker build --target test front` 성공을 확인했다 (MR verify와 같은 검증)
- [ ] `git diff feat/lecture-voice --stat`에 `front/apps/web` 밖의 파일이 없음을 확인했다
- [ ] Task 5 E2E 결과를 MR 본문에 적었다
- [ ] push·MR 등록은 사람이 한다 (`feat/lecture-voice-web` → `feat/lecture-voice`)

## 알려진 한계

- 발언 허용·손들기는 저장하지 않는다 — 학생이 재접속하면 새 토큰이라 초기화된다(설계 §9).
- 답안 상태·장치 연결 점은 계속 목이다. 예시 답안은 userId로 고른 고정 값이라 실제와 무관하다.
- 학생 오디오 요소는 문서에 붙이지 않는다(LiveKit이 `play()`를 부른다). 출력 장치 선택·학생별 볼륨은 범위 밖이다.
- 자동 재생 해제는 "수업 시작" 클릭에서 이어진 `startAudio()`와, 막혔을 때의 "학생 소리 켜기" 버튼 두 경로뿐이다. 클릭에서 접속까지 오래 걸리면 브라우저의 일시적 사용자 활성화가 만료돼 버튼이 보일 수 있다.
