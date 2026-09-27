# 실시간 수업 학습자 앱 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학습자 앱의 수업 흐름(코드 입장 → 대기 → 풀이)을 실제 backend와 OpenVidu(LiveKit)에 연결해, 학습자가 강사 음성을 듣고 강사가 종료하면 종료 화면으로 가게 한다.

**Architecture:** 목 모듈 `classService.ts`를 실제 API 호출로 바꾸고, LiveKit 방 연결은 화면과 분리된 모듈(`liveLecture.ts`)이 소유한다. 방 이벤트 해석(메타데이터 → 수업 시작, 연결 해제 사유 → 종료/끊김)은 순수 함수(`lectureSignals.ts`)로 떼어 네이티브 없이 테스트한다. 문제 풀이는 지금처럼 목 문제를 쓴다.

**Tech Stack:** Expo 57 (CNG, `expo run:android`), React Native 0.86.3, expo-router, zustand, zod(`@ieum/contracts`), `livekit-client@2.22.3`, `@livekit/react-native@3.0.0`, `@livekit/react-native-webrtc@144.2.0`, `@livekit/react-native-expo-plugin@1.0.3`, `@config-plugins/react-native-webrtc@15.0.2`, jest-expo

**Spec:** `docs/superpowers/specs/2026-09-26-live-lecture-mvp-design.md` §2, §7 / 계약 원본 `docs/product/api-spec.md` §8.2, §8.5 (dev 병합 후 dev 기준)

## Global Constraints

- 브랜치: `feat/lecture-app` (최신 `dev`에서 분기 — `feat/lecture-api` MR이 `dev`에 병합된 뒤 시작, 작업 폴더 `../wt-lecture-app`), MR 대상 `dev`. 분기 직후 `git checkout feat/lecture -- docs/superpowers/plans/2026-09-26-lecture-web.md docs/superpowers/plans/2026-09-26-lecture-app.md`로 계획 문서를 가져와 커밋한다(웹 브랜치와 같은 내용이라 나중에 병합되는 쪽은 충돌 없이 합쳐진다. 이미 dev에 있으면 건너뛴다)
- 작업 위치: `front/apps/mobile`, `front/packages/contracts` 만. backend·web 파일은 건드리지 않는다
- 커밋: `type(lecture): 한글 제목` + 빈 줄 + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- API (context-path `/api/v1` 포함, baseUrl에 이미 있음):
  - `POST /lecture-participations` `{participationCode}` → 201/200 학습자 스냅샷
  - `GET /lectures/{lectureId}` → 학습자 스냅샷
  - `POST /lectures/{lectureId}/media-tokens` → 201 `{serverUrl, token, expiresAt}`
- 학습자 스냅샷 필드: `lectureId`(문자열), `title`, `description`, `status`(`SCHEDULED|OPEN|CLOSED`), `scheduledAt`, `startedAt`, `endedAt`, `currentProblem`(null), `createdAt`, `myParticipation{joinedAt, leftAt}`, `reviewableCurriculumStages`(배열). `participationCode`는 없다
- 오류 코드: 없는 코드·종료 수업 `LECTURE_NOT_JOINABLE`(404), 강사 계정 `LEARNER_ACCESS_REQUIRED`(403), 퇴장 후 토큰 `LECTURE_PARTICIPATION_REQUIRED`(403), 종료 수업 토큰 `INVALID_LECTURE_STATE`(409), 음성 서버 장애 `MEDIA_SERVER_UNAVAILABLE`(503)
- 수업 시작 신호: 방 metadata `{"status":"OPEN"}`(접속 시 `room.metadata` 또는 `RoomEvent.RoomMetadataChanged`). 재접속 시에는 `GET /lectures/{id}`의 `status`가 기준
- 수업 종료 신호: `RoomEvent.Disconnected` 사유 `DisconnectReason.ROOM_DELETED` → `/class/end`. 그 밖의 해제 → `/class/disconnected` (음성 장애를 수업 종료로 오인하지 않는다)
- 학습자 토큰은 수신 전용·숨김이다. 앱은 마이크를 publish하지 않는다
- 서비스는 응답을 `@ieum/contracts` zod 스키마로 파싱한다(기존 `learningService`/`authService` 관례)
- 검증: `cd front && pnpm --filter @ieum/mobile exec jest` 와 `pnpm --filter @ieum/mobile typecheck` 통과
- `.env*` 파일은 읽거나 고치지 않는다

## File Structure

| 파일 | 책임 |
| --- | --- |
| `front/packages/contracts/src/index.ts` (수정) | 학습자 수업 스냅샷·음성 토큰 zod 스키마 |
| `front/apps/mobile/src/services/classService.ts` (수정) | 코드 입장·스냅샷 조회·토큰 발급(실제 API). 문제·복습 목은 유지 |
| `front/apps/mobile/src/services/lectureSignals.ts` (신규) | 방 metadata·연결 해제 사유 해석 (순수 함수) |
| `front/apps/mobile/src/services/liveLecture.ts` (신규) | LiveKit 방 소유: 접속·이벤트 연결·퇴장, 오디오 세션 |
| `front/apps/mobile/src/app/_layout.tsx` (수정) | 앱 시작 시 `registerGlobals()` |
| `front/apps/mobile/app.json`, `package.json` (수정) | LiveKit 의존성과 config plugin |
| `front/apps/mobile/src/app/(tabs)/class.tsx`, `class/waiting.tsx`, `class/disconnected.tsx`, `class/end.tsx` (수정) | 화면 흐름 연결 |
| `front/apps/mobile/__tests__/lectureContracts.test.ts`, `classService.test.ts`, `lectureSignals.test.ts`, `liveLecture.test.ts` (신규) | 테스트 |

---

### Task 1: 수업 계약 스키마

**Files:**
- Modify: `front/packages/contracts/src/index.ts` (파일 끝에 추가)
- Test: `front/apps/mobile/__tests__/lectureContracts.test.ts`

**Interfaces:**
- Produces: `lectureStatusSchema`, `LectureStatus`(`"SCHEDULED" | "OPEN" | "CLOSED"`), `learnerLectureSchema`, `LearnerLecture`, `mediaTokenSchema`, `MediaToken`

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/mobile/__tests__/lectureContracts.test.ts`:

```ts
import { learnerLectureSchema, mediaTokenSchema } from "@ieum/contracts";

const learner = {
  lectureId: "42",
  title: "약자 4회차",
  description: null,
  status: "SCHEDULED",
  scheduledAt: null,
  startedAt: null,
  endedAt: null,
  currentProblem: null,
  createdAt: "2026-09-26T01:00:00.000Z",
  myParticipation: { joinedAt: "2026-09-26T01:00:00.000Z", leftAt: null },
  reviewableCurriculumStages: [],
};

describe("수업 계약", () => {
  it("학습자 스냅샷을 파싱한다", () => {
    const parsed = learnerLectureSchema.parse(learner);
    expect(parsed.lectureId).toBe("42");
    expect(parsed.status).toBe("SCHEDULED");
    expect(parsed.myParticipation.leftAt).toBeNull();
  });

  it("알 수 없는 상태는 거부한다", () => {
    expect(() => learnerLectureSchema.parse({ ...learner, status: "PAUSED" })).toThrow();
  });

  it("음성 토큰 응답을 파싱한다", () => {
    const parsed = mediaTokenSchema.parse({
      serverUrl: "wss://openvidu.i-eum.site",
      token: "jwt",
      expiresAt: "2026-09-26T01:10:00.000Z",
    });
    expect(parsed.serverUrl).toBe("wss://openvidu.i-eum.site");
  });

  it("wss가 아닌 서버 주소는 거부한다", () => {
    expect(() =>
      mediaTokenSchema.parse({ serverUrl: "http://x", token: "jwt", expiresAt: "2026-09-26T01:10:00.000Z" }),
    ).toThrow();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureContracts.test.ts`
Expected: FAIL — `learnerLectureSchema` 가 export되지 않았다

- [ ] **Step 3: 구현**

`front/packages/contracts/src/index.ts` 끝에 추가(파일 상단의 `z` import를 그대로 쓴다):

```ts
/* ─── 실시간 수업 (API 명세 8.2·8.5절) ─── */

export const lectureStatusSchema = z.enum(["SCHEDULED", "OPEN", "CLOSED"]);
export type LectureStatus = z.infer<typeof lectureStatusSchema>;

/** 참여 학습자용 수업 Snapshot. 참여 코드·다른 참여자 정보는 오지 않는다 */
export const learnerLectureSchema = z.object({
  /** BIGINT의 10진수 문자열. number로 바꾸지 않는다 */
  lectureId: z.string(),
  title: z.string(),
  description: z.string().nullable(),
  status: lectureStatusSchema,
  scheduledAt: z.string().nullable(),
  startedAt: z.string().nullable(),
  endedAt: z.string().nullable(),
  currentProblem: z.unknown().nullable(),
  createdAt: z.string(),
  myParticipation: z.object({ joinedAt: z.string(), leftAt: z.string().nullable() }),
  reviewableCurriculumStages: z.array(z.unknown()),
});
export type LearnerLecture = z.infer<typeof learnerLectureSchema>;

/** OpenVidu 음성 접속 토큰. 서버에 저장되지 않으며 10분 유효 */
export const mediaTokenSchema = z.object({
  serverUrl: z.string().regex(/^wss?:\/\//),
  token: z.string().min(1),
  expiresAt: z.string(),
});
export type MediaToken = z.infer<typeof mediaTokenSchema>;
```

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureContracts.test.ts && pnpm --filter @ieum/contracts typecheck`
Expected: PASS (4 tests), typecheck 오류 없음

- [ ] **Step 5: Commit**

```bash
git add front/packages/contracts/src/index.ts front/apps/mobile/__tests__/lectureContracts.test.ts
git commit -m "feat(lecture): 학습자 수업 스냅샷과 음성 토큰 계약을 추가한다"
```

---

### Task 2: classService를 실제 API로 바꾼다

**Files:**
- Modify: `front/apps/mobile/src/services/classService.ts`
- Test: `front/apps/mobile/__tests__/classService.test.ts`

**Interfaces:**
- Consumes: Task 1 `learnerLectureSchema`, `mediaTokenSchema`; 기존 `api` (`@/services/apiClient`, `get/post/put`, 실패 시 `ApiError{status, code, message}`)
- Produces:
  - `interface ClassInfo { lectureId: string; title: string; status: LectureStatus }`
  - `classService.joinByCode(code: string): Promise<ClassInfo>`
  - `classService.getLecture(lectureId: string): Promise<ClassInfo>`
  - `classService.issueMediaToken(lectureId: string): Promise<MediaToken>`
  - 유지(목): `classService.getClassSession(): Promise<LearningSession>`, `classService.recentClass`, `classService.reviewOptions`
  - `joinErrorMessage(error: unknown): string` — 입장 실패 안내 문구

- [ ] **Step 1: 기존 서비스 테스트의 모킹 방식 확인**

`front/apps/mobile/__tests__/learningService.test.ts` 를 읽고 `api`를 어떻게 대체하는지(`jest.mock` 경로·형태) 확인한다. 아래 테스트는 `jest.mock("@/services/apiClient")` 방식으로 썼다. `@/` 별칭이 jest에서 풀리지 않으면 그 테스트가 쓰는 상대 경로(`../src/services/apiClient`)로 바꾼다.

- [ ] **Step 2: 실패하는 테스트 작성**

`front/apps/mobile/__tests__/classService.test.ts`:

```ts
import { ApiError } from "@ieum/api-client";
import { api } from "@/services/apiClient";
import { classService, joinErrorMessage } from "@/services/classService";

jest.mock("@/services/apiClient", () => ({
  api: { get: jest.fn(), post: jest.fn(), put: jest.fn() },
}));

const mockedApi = api as unknown as { get: jest.Mock; post: jest.Mock };

const snapshot = (status: string) => ({
  lectureId: "42",
  title: "약자 4회차",
  description: null,
  status,
  scheduledAt: null,
  startedAt: null,
  endedAt: null,
  currentProblem: null,
  createdAt: "2026-09-26T01:00:00.000Z",
  myParticipation: { joinedAt: "2026-09-26T01:00:00.000Z", leftAt: null },
  reviewableCurriculumStages: [],
});

beforeEach(() => jest.resetAllMocks());

describe("classService", () => {
  it("코드로 입장하면 수업 식별자와 상태를 돌려준다", async () => {
    mockedApi.post.mockResolvedValue(snapshot("SCHEDULED"));

    const info = await classService.joinByCode("012345");

    expect(mockedApi.post).toHaveBeenCalledWith("/lecture-participations", { participationCode: "012345" });
    expect(info).toEqual({ lectureId: "42", title: "약자 4회차", status: "SCHEDULED" });
  });

  it("스냅샷을 다시 조회한다", async () => {
    mockedApi.get.mockResolvedValue(snapshot("OPEN"));

    const info = await classService.getLecture("42");

    expect(mockedApi.get).toHaveBeenCalledWith("/lectures/42");
    expect(info.status).toBe("OPEN");
  });

  it("음성 토큰을 발급받는다", async () => {
    mockedApi.post.mockResolvedValue({
      serverUrl: "wss://openvidu.i-eum.site",
      token: "jwt",
      expiresAt: "2026-09-26T01:10:00.000Z",
    });

    const token = await classService.issueMediaToken("42");

    expect(mockedApi.post).toHaveBeenCalledWith("/lectures/42/media-tokens");
    expect(token.token).toBe("jwt");
  });

  it("응답 형식이 계약과 다르면 실패한다", async () => {
    mockedApi.post.mockResolvedValue({ lectureId: 42 });

    await expect(classService.joinByCode("012345")).rejects.toThrow();
  });

  it("입장 실패 사유를 사용자 문구로 바꾼다", () => {
    expect(joinErrorMessage(new ApiError(404, "LECTURE_NOT_JOINABLE", "x"))).toBe(
      "입장할 수 있는 수업이 없어요. 참여 코드를 확인해 주세요.",
    );
    expect(joinErrorMessage(new ApiError(403, "LEARNER_ACCESS_REQUIRED", "x"))).toBe(
      "학습자 계정으로만 수업에 입장할 수 있어요.",
    );
    expect(joinErrorMessage(new ApiError(0, "NETWORK_UNAVAILABLE", "x"))).toBe(
      "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.",
    );
    expect(joinErrorMessage(new Error("?"))).toBe("입장하지 못했어요. 다시 시도해 주세요.");
  });
});
```

- [ ] **Step 3: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/classService.test.ts`
Expected: FAIL — `getLecture`, `issueMediaToken`, `joinErrorMessage` 가 없고 `joinByCode`가 목 값을 돌려준다

- [ ] **Step 4: 구현**

`front/apps/mobile/src/services/classService.ts` 를 다음으로 바꾼다. `getClassSession`·`recentClass`·`reviewOptions`의 목 본문은 **기존 파일 내용을 그대로** 옮긴다(아래에서 `/* 기존 목 그대로 */`로 표시한 자리).

```ts
import { ApiError } from "@ieum/api-client";
import {
  learnerLectureSchema,
  mediaTokenSchema,
  type LearnerLecture,
  type LearningSession,
  type LectureStatus,
  type MediaToken,
} from "@ieum/contracts";
import { dotsToCell } from "@ieum/domain";
import { api } from "./apiClient";

export interface ClassInfo {
  lectureId: string;
  title: string;
  status: LectureStatus;
}

function toClassInfo(lecture: LearnerLecture): ClassInfo {
  return { lectureId: lecture.lectureId, title: lecture.title, status: lecture.status };
}

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** 수업(LIVE) 서비스 — 입장·스냅샷·음성 토큰은 실제 API, 문제 출제와 복습 후보는 아직 목이다 */
export const classService = {
  /** 6자리 참여 코드로 입장한다. 최초 입장이든 재입장이든 같은 스냅샷을 받는다 (API 명세 8.2절) */
  async joinByCode(code: string): Promise<ClassInfo> {
    const payload = await api.post<unknown>("/lecture-participations", { participationCode: code });
    return toClassInfo(learnerLectureSchema.parse(payload));
  },

  /** 재접속·상태 확인용. 수업 상태의 권위는 이 응답이다 (API 명세 8.5절) */
  async getLecture(lectureId: string): Promise<ClassInfo> {
    const payload = await api.get<unknown>(`/lectures/${lectureId}`);
    return toClassInfo(learnerLectureSchema.parse(payload));
  },

  /** OpenVidu 수신 전용 토큰. 재접속할 때마다 새로 받는다 */
  async issueMediaToken(lectureId: string): Promise<MediaToken> {
    const payload = await api.post<unknown>(`/lectures/${lectureId}/media-tokens`);
    return mediaTokenSchema.parse(payload);
  },

  /* ─── 아래는 아직 목이다. 대응 엔드포인트(문제 출제·복습 연결)가 없다 ─── */

  async getClassSession(): Promise<LearningSession> {
    /* 기존 목 그대로 */
  },

  recentClass: /* 기존 목 그대로 */,

  reviewOptions: /* 기존 목 그대로 */,
};

/** 입장 실패 안내. 서버 message를 그대로 읽어 주지 않고 사유별 문구를 쓴다 */
export function joinErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "LECTURE_NOT_JOINABLE") return "입장할 수 있는 수업이 없어요. 참여 코드를 확인해 주세요.";
    if (error.code === "LEARNER_ACCESS_REQUIRED") return "학습자 계정으로만 수업에 입장할 수 있어요.";
    if (error.code === "NETWORK_UNAVAILABLE") return "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.";
  }
  return "입장하지 못했어요. 다시 시도해 주세요.";
}
```

`delay`·`dotsToCell`은 기존 목 본문이 쓰므로 남긴다. 목 본문이 쓰지 않게 되면 import를 지운다.

- [ ] **Step 5: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/classService.test.ts`
Expected: PASS (5 tests)

이 시점에 `class.tsx`가 옛 `ClassInfo.code/teacher` 필드를 쓰면 typecheck가 깨진다. Task 6에서 화면을 고치므로, 여기서는 jest만 통과시키고 typecheck 오류는 Task 6에서 해소한다(보고서에 남긴다).

- [ ] **Step 6: Commit**

```bash
git add front/apps/mobile/src/services/classService.ts front/apps/mobile/__tests__/classService.test.ts
git commit -m "feat(lecture): 수업 입장과 음성 토큰 발급을 실제 API로 바꾼다"
```

---

### Task 3: 방 신호 해석 (순수 함수)

**Files:**
- Create: `front/apps/mobile/src/services/lectureSignals.ts`
- Test: `front/apps/mobile/__tests__/lectureSignals.test.ts`

**Interfaces:**
- Produces:
  - `isLectureOpen(metadata: string | undefined | null): boolean`
  - `type RoomExit = "ended" | "lost" | "left"`
  - `classifyDisconnect(reason: number | undefined, leftByUser: boolean): RoomExit`
  - `ROOM_DELETED_REASON` — livekit-client `DisconnectReason.ROOM_DELETED` 값과 같은 숫자

- [ ] **Step 1: DisconnectReason 값 확인**

`front/node_modules/livekit-client` 가 아직 없으므로(Task 5에서 설치) livekit protocol 정의의 값을 쓴다: `DisconnectReason.ROOM_DELETED = 5`, `CLIENT_INITIATED = 1`. Task 5 설치 후 `grep -n "ROOM_DELETED" front/node_modules/@livekit/protocol/dist/*.d.ts` 로 값이 5인지 재확인하는 단계가 Task 5에 있다.

- [ ] **Step 2: 실패하는 테스트 작성**

`front/apps/mobile/__tests__/lectureSignals.test.ts`:

```ts
import { classifyDisconnect, isLectureOpen, ROOM_DELETED_REASON } from "@/services/lectureSignals";

describe("isLectureOpen", () => {
  it("OPEN 메타데이터를 인식한다", () => {
    expect(isLectureOpen('{"status":"OPEN"}')).toBe(true);
  });

  it("비었거나 다른 상태면 시작 전이다", () => {
    expect(isLectureOpen(undefined)).toBe(false);
    expect(isLectureOpen("")).toBe(false);
    expect(isLectureOpen('{"status":"SCHEDULED"}')).toBe(false);
  });

  it("JSON이 아니면 시작 전으로 본다", () => {
    expect(isLectureOpen("OPEN")).toBe(false);
  });
});

describe("classifyDisconnect", () => {
  it("방 삭제는 수업 종료다", () => {
    expect(classifyDisconnect(ROOM_DELETED_REASON, false)).toBe("ended");
  });

  it("사용자가 나간 것은 퇴장이다", () => {
    expect(classifyDisconnect(1, true)).toBe("left");
  });

  it("그 밖의 해제는 연결 끊김이다", () => {
    expect(classifyDisconnect(undefined, false)).toBe("lost");
    expect(classifyDisconnect(2, false)).toBe("lost");
  });

  it("방 삭제가 사용자 퇴장보다 우선한다", () => {
    expect(classifyDisconnect(ROOM_DELETED_REASON, true)).toBe("ended");
  });
});
```

- [ ] **Step 3: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureSignals.test.ts`
Expected: FAIL — 모듈이 없다

- [ ] **Step 4: 구현**

`front/apps/mobile/src/services/lectureSignals.ts`:

```ts
/**
 * OpenVidu 방 신호 해석 (API 명세 8.5절). 네이티브 없이 테스트하려고 LiveKit 타입에 의존하지 않는다.
 *
 * 시작: 강사가 수업을 시작하면 서버가 방 metadata를 {"status":"OPEN"}으로 바꾼다.
 * 종료: 강사가 수업을 끝내면 서버가 방을 삭제하고, 참가자는 ROOM_DELETED 사유로 끊긴다.
 */

/** livekit protocol DisconnectReason.ROOM_DELETED */
export const ROOM_DELETED_REASON = 5;

export type RoomExit = "ended" | "lost" | "left";

export function isLectureOpen(metadata: string | undefined | null): boolean {
  if (!metadata) return false;
  try {
    const parsed: unknown = JSON.parse(metadata);
    return typeof parsed === "object" && parsed !== null && (parsed as { status?: unknown }).status === "OPEN";
  } catch {
    return false;
  }
}

/** 음성 장애를 수업 종료로 오인하지 않는다 — 종료는 방 삭제일 때만이다 */
export function classifyDisconnect(reason: number | undefined, leftByUser: boolean): RoomExit {
  if (reason === ROOM_DELETED_REASON) return "ended";
  if (leftByUser) return "left";
  return "lost";
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/lectureSignals.test.ts`
Expected: PASS (7 tests)

- [ ] **Step 6: Commit**

```bash
git add front/apps/mobile/src/services/lectureSignals.ts front/apps/mobile/__tests__/lectureSignals.test.ts
git commit -m "feat(lecture): 음성 방의 시작·종료 신호를 해석한다"
```

---

### Task 4: LiveKit 의존성과 네이티브 설정

**Files:**
- Modify: `front/apps/mobile/package.json` (pnpm이 수정), `front/pnpm-lock.yaml`
- Modify: `front/apps/mobile/app.json` (`expo.plugins`)
- Modify: `front/apps/mobile/src/app/_layout.tsx` (파일 최상단)

**Interfaces:**
- Produces: 앱 전역에서 `livekit-client`의 `Room`이 React Native에서 동작하는 상태 (`registerGlobals()` 호출됨)

- [ ] **Step 1: 의존성 설치**

```bash
cd front
pnpm --filter @ieum/mobile add livekit-client@2.22.3 @livekit/react-native@3.0.0 @livekit/react-native-webrtc@144.2.0 @livekit/react-native-expo-plugin@1.0.3 @config-plugins/react-native-webrtc@15.0.2
```

설치 후 `ROOM_DELETED` 값을 확인한다(Task 3의 상수가 맞는지):

```bash
grep -rn "ROOM_DELETED = " node_modules/@livekit/protocol/dist/ | head -3
```

Expected: `ROOM_DELETED = 5`. 다르면 `lectureSignals.ts`의 상수와 테스트를 그 값으로 고치고 보고서에 남긴다.

- [ ] **Step 2: config plugin 등록**

`front/apps/mobile/app.json` 의 `expo.plugins` 배열 끝에 두 항목을 추가한다(기존 항목은 그대로):

```json
      "@livekit/react-native-expo-plugin",
      "@config-plugins/react-native-webrtc"
```

`android.permissions`에는 `RECORD_AUDIO`가 이미 있다. 수신 전용이라 카메라 권한은 추가하지 않는다. 플러그인이 `CAMERA` 등을 자동으로 넣으면 그대로 두고 보고서에 남긴다.

- [ ] **Step 3: 앱 시작 시 WebRTC 전역 등록**

`front/apps/mobile/src/app/_layout.tsx` 의 **첫 import 위**에 추가한다:

```ts
import { registerGlobals } from "@livekit/react-native";

// livekit-client가 React Native에서 WebRTC를 쓰려면 다른 모듈보다 먼저 전역을 등록해야 한다.
registerGlobals();
```

- [ ] **Step 4: 기존 테스트가 깨지지 않는지 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest`
Expected: 기존 테스트 + Task 1~3 테스트 모두 PASS. `_layout.tsx`를 import하는 테스트는 없으므로 네이티브 모듈 로드 오류가 없어야 한다. 오류가 나면 해당 테스트 파일 상단에 `jest.mock("@livekit/react-native", () => ({ registerGlobals: jest.fn(), AudioSession: { startAudioSession: jest.fn(), stopAudioSession: jest.fn() } }))`를 추가한다.

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/package.json front/pnpm-lock.yaml front/apps/mobile/app.json front/apps/mobile/src/app/_layout.tsx
git commit -m "chore(lecture): 앱에 LiveKit 음성 의존성과 네이티브 설정을 추가한다"
```

---

### Task 5: 음성 방 연결 모듈

**Files:**
- Create: `front/apps/mobile/src/services/liveLecture.ts`
- Test: `front/apps/mobile/__tests__/liveLecture.test.ts`

**Interfaces:**
- Consumes: Task 2 `classService.getLecture/issueMediaToken`, Task 3 `isLectureOpen`, `classifyDisconnect`, `RoomExit`
- Produces:
  - `interface LiveLectureHandlers { onOpen: () => void; onExit: (exit: RoomExit) => void }`
  - `liveLecture.join(lectureId: string, handlers: LiveLectureHandlers): Promise<void>` — 토큰 발급 → 오디오 세션 시작 → 방 접속. 이미 OPEN이면 접속 직후 `onOpen` 호출
  - `liveLecture.leave(): Promise<void>` — 사용자 퇴장 (`onExit("left")` 한 번)
  - `liveLecture.currentLectureId(): string | null`
  - 규칙: `onOpen`은 접속 1회당 최대 1번. `onExit`도 1번. `join`을 다시 부르면 이전 방을 먼저 끊는다(이전 핸들러는 호출하지 않는다)

- [ ] **Step 1: 실패하는 테스트 작성**

`front/apps/mobile/__tests__/liveLecture.test.ts`:

```ts
import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";

type Listener = (...args: unknown[]) => void;

const mockRooms: MockRoom[] = [];

class MockRoom {
  metadata: string | undefined = undefined;
  listeners = new Map<string, Listener[]>();
  connect = jest.fn(async () => undefined);
  disconnect = jest.fn(async () => {
    this.emit("disconnected", 1);
  });
  constructor() {
    mockRooms.push(this);
  }
  on(event: string, listener: Listener) {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener]);
    return this;
  }
  removeAllListeners() {
    this.listeners.clear();
    return this;
  }
  emit(event: string, ...args: unknown[]) {
    (this.listeners.get(event) ?? []).forEach((l) => l(...args));
  }
}

jest.mock("livekit-client", () => ({
  Room: jest.fn().mockImplementation(() => new MockRoom()),
  RoomEvent: { RoomMetadataChanged: "roomMetadataChanged", Disconnected: "disconnected" },
}));

jest.mock("@livekit/react-native", () => ({
  AudioSession: { startAudioSession: jest.fn(async () => undefined), stopAudioSession: jest.fn(async () => undefined) },
}));

jest.mock("@/services/classService", () => ({
  classService: { getLecture: jest.fn(), issueMediaToken: jest.fn() },
}));

const mockedClass = classService as unknown as { getLecture: jest.Mock; issueMediaToken: jest.Mock };

function handlers() {
  return { onOpen: jest.fn(), onExit: jest.fn() };
}

beforeEach(async () => {
  await liveLecture.leave();
  mockRooms.length = 0;
  jest.clearAllMocks();
  mockedClass.issueMediaToken.mockResolvedValue({ serverUrl: "wss://s", token: "t", expiresAt: "x" });
});

describe("liveLecture", () => {
  it("토큰으로 방에 접속한다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const h = handlers();

    await liveLecture.join("42", h);

    expect(mockRooms[0].connect).toHaveBeenCalledWith("wss://s", "t");
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(liveLecture.currentLectureId()).toBe("42");
  });

  it("이미 시작된 수업이면 접속 직후 시작을 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();

    await liveLecture.join("42", h);

    expect(h.onOpen).toHaveBeenCalledTimes(1);
  });

  it("방 메타데이터가 OPEN으로 바뀌면 한 번만 시작을 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const h = handlers();
    await liveLecture.join("42", h);

    mockRooms[0].emit("roomMetadataChanged", '{"status":"OPEN"}');
    mockRooms[0].emit("roomMetadataChanged", '{"status":"OPEN"}');

    expect(h.onOpen).toHaveBeenCalledTimes(1);
  });

  it("방이 삭제되면 수업 종료를 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();
    await liveLecture.join("42", h);

    mockRooms[0].emit("disconnected", 5);

    expect(h.onExit).toHaveBeenCalledWith("ended");
    expect(liveLecture.currentLectureId()).toBeNull();
  });

  it("그 밖의 해제는 연결 끊김으로 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();
    await liveLecture.join("42", h);

    mockRooms[0].emit("disconnected", 2);

    expect(h.onExit).toHaveBeenCalledWith("lost");
  });

  it("사용자가 나가면 퇴장으로 한 번만 알린다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    const h = handlers();
    await liveLecture.join("42", h);

    await liveLecture.leave();

    expect(h.onExit).toHaveBeenCalledTimes(1);
    expect(h.onExit).toHaveBeenCalledWith("left");
  });

  it("다시 입장하면 이전 방을 끊되 이전 핸들러는 부르지 않는다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "SCHEDULED" });
    const first = handlers();
    await liveLecture.join("42", first);

    await liveLecture.join("42", handlers());

    expect(mockRooms[0].disconnect).toHaveBeenCalled();
    expect(first.onExit).not.toHaveBeenCalled();
  });

  it("토큰 발급이 실패하면 방에 접속하지 않고 오류를 던진다", async () => {
    mockedClass.getLecture.mockResolvedValue({ lectureId: "42", title: "t", status: "OPEN" });
    mockedClass.issueMediaToken.mockRejectedValue(new Error("503"));

    await expect(liveLecture.join("42", handlers())).rejects.toThrow("503");
    expect(mockRooms).toHaveLength(0);
    expect(liveLecture.currentLectureId()).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveLecture.test.ts`
Expected: FAIL — 모듈이 없다

- [ ] **Step 3: 구현**

`front/apps/mobile/src/services/liveLecture.ts`:

```ts
import { AudioSession } from "@livekit/react-native";
import { Room, RoomEvent } from "livekit-client";
import { classService } from "./classService";
import { classifyDisconnect, isLectureOpen, type RoomExit } from "./lectureSignals";

export interface LiveLectureHandlers {
  /** 수업이 시작됐다 (접속 1회당 최대 1번) */
  onOpen: () => void;
  /** 방에서 나왔다 — ended: 강사가 종료, lost: 음성 연결 끊김, left: 사용자가 나감 (1번) */
  onExit: (exit: RoomExit) => void;
}

interface Session {
  lectureId: string;
  room: Room;
  handlers: LiveLectureHandlers;
  opened: boolean;
  exited: boolean;
  leavingByUser: boolean;
}

/**
 * 수업 음성 방. 화면을 넘나들며 유지되므로 컴포넌트가 아니라 이 모듈이 소유한다.
 * 학습자 토큰은 수신 전용이라 마이크를 publish하지 않는다. 원격 오디오는 자동 구독·재생된다.
 */
let session: Session | null = null;

function markOpen(current: Session): void {
  if (current.opened || current.exited) return;
  current.opened = true;
  current.handlers.onOpen();
}

function finish(current: Session, exit: RoomExit): void {
  if (current.exited) return;
  current.exited = true;
  current.room.removeAllListeners();
  if (session === current) session = null;
  void AudioSession.stopAudioSession();
  current.handlers.onExit(exit);
}

async function discardSilently(previous: Session): Promise<void> {
  // 이전 핸들러가 불리지 않게 먼저 표시하고 끊는다
  previous.exited = true;
  previous.room.removeAllListeners();
  if (session === previous) session = null;
  await previous.room.disconnect();
}

export const liveLecture = {
  async join(lectureId: string, handlers: LiveLectureHandlers): Promise<void> {
    if (session) await discardSilently(session);

    // 상태의 권위는 HTTP 스냅샷이다. 재접속이면 metadata보다 먼저 확인한다 (API 명세 8.5절)
    const lecture = await classService.getLecture(lectureId);
    const token = await classService.issueMediaToken(lectureId);

    await AudioSession.startAudioSession();
    const room = new Room();
    const current: Session = { lectureId, room, handlers, opened: false, exited: false, leavingByUser: false };
    session = current;

    room.on(RoomEvent.RoomMetadataChanged, (metadata: string) => {
      if (isLectureOpen(metadata)) markOpen(current);
    });
    room.on(RoomEvent.Disconnected, (reason?: number) => {
      finish(current, classifyDisconnect(reason, current.leavingByUser));
    });

    try {
      await room.connect(token.serverUrl, token.token);
    } catch (error) {
      current.exited = true;
      room.removeAllListeners();
      if (session === current) session = null;
      void AudioSession.stopAudioSession();
      throw error;
    }

    if (lecture.status === "OPEN" || isLectureOpen(room.metadata)) markOpen(current);
  },

  async leave(): Promise<void> {
    const current = session;
    if (!current) return;
    current.leavingByUser = true;
    await current.room.disconnect();
    // disconnect가 Disconnected 이벤트를 내지 않는 경우에도 한 번은 알린다
    finish(current, "left");
  },

  currentLectureId(): string | null {
    return session?.lectureId ?? null;
  },
};
```

`AudioSession.startAudioSession()`은 토큰을 받은 **뒤**에 부르므로, 토큰 실패 테스트에서 오디오 세션이 열리지 않는다. `new Room()`도 토큰 뒤라 실패 시 `mockRooms`가 비어 있다.

- [ ] **Step 4: 통과 확인**

Run: `cd front && pnpm --filter @ieum/mobile exec jest __tests__/liveLecture.test.ts`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add front/apps/mobile/src/services/liveLecture.ts front/apps/mobile/__tests__/liveLecture.test.ts
git commit -m "feat(lecture): 수업 음성 방 접속과 시작·종료 알림을 연결한다"
```

---

### Task 6: 화면 흐름 연결

**Files:**
- Modify: `front/apps/mobile/src/app/(tabs)/class.tsx`
- Modify: `front/apps/mobile/src/app/class/waiting.tsx`
- Modify: `front/apps/mobile/src/app/class/disconnected.tsx`
- Modify: `front/apps/mobile/src/app/class/end.tsx`
- Modify: `front/apps/mobile/src/app/class/play.tsx` (나가기 경로만)

**Interfaces:**
- Consumes: Task 2 `classService.joinByCode`, `joinErrorMessage`, `ClassInfo`; Task 5 `liveLecture.join/leave/currentLectureId`
- Produces: 라우트 파라미터 `/class/waiting?lectureId=&title=`

각 화면을 먼저 읽고, 아래 변경만 한다. 문구·접근성 낭독(`speak`/`announce`)·스타일 등 기존 코드는 그대로 둔다.

- [ ] **Step 1: 코드 입장 화면 (`(tabs)/class.tsx`)**

- `classService.joinByCode(code)` 결과(`ClassInfo`)로 이동: `router.push({ pathname: "/class/waiting", params: { lectureId: info.lectureId, title: info.title } })`.
- 실패 시 기존 오류 표시 자리에 `joinErrorMessage(error)` 문구를 쓰고, 화면이 쓰는 낭독 함수로 같은 문구를 읽어 준다(기존 오류 처리 방식이 있으면 그것을 따른다).
- 옛 `ClassInfo.code`/`teacher` 필드를 쓰던 곳은 `title`만 쓰도록 고친다.

- [ ] **Step 2: 대기 화면 (`class/waiting.tsx`)**

기존의 "3초 대기 후 `getClassSession()` → `start(session, "class")` → `/class/play`" 흐름을 다음으로 바꾼다. 안내 낭독(`speakAsync`)은 유지한다.

```tsx
import { router, useLocalSearchParams } from "expo-router";
import { useEffect, useState } from "react";
import { classService } from "@/services/classService";
import { liveLecture } from "@/services/liveLecture";
import { useSessionStore } from "@/stores/sessionStore";

// 컴포넌트 안:
const { lectureId, title } = useLocalSearchParams<{ lectureId: string; title?: string }>();
const start = useSessionStore((s) => s.start);
const [failed, setFailed] = useState<string | null>(null);

useEffect(() => {
  let cancelled = false;
  liveLecture
    .join(lectureId, {
      onOpen: () => {
        // 문제 출제는 아직 목이다 — 수업이 시작되면 목 문제로 풀이를 시작한다
        void classService.getClassSession().then((session) => {
          if (cancelled) return;
          start(session, "class");
          router.replace("/class/play");
        });
      },
      onExit: (exit) => {
        if (exit === "ended") router.replace("/class/end");
        else if (exit === "lost") router.replace({ pathname: "/class/disconnected", params: { lectureId, title } });
      },
    })
    .catch(() => {
      if (!cancelled) setFailed("음성 수업에 연결하지 못했어요. 다시 시도해 주세요.");
    });
  return () => {
    cancelled = true;
  };
}, [lectureId]);
```

- `onExit` 핸들러는 대기 화면을 떠난 뒤(풀이 화면)에도 살아 있어야 하므로, 언마운트 cleanup에서 `leave()`를 부르지 **않는다**.
- 나가기 버튼: 기존 `router.back()` 전에 `await liveLecture.leave()`.
- `failed`가 있으면 기존 안내 영역에 문구를 보여 주고 낭독하며, "다시 시도" 버튼은 같은 `join`을 다시 부른다(기존 버튼 컴포넌트 재사용).
- 기존 3초 최소 대기(`minWait`)는 제거한다. 시작 시점은 이제 강사가 정한다.

- [ ] **Step 3: 연결 끊김 화면 (`class/disconnected.tsx`)**

- "지금 다시 연결": `router.replace({ pathname: "/class/waiting", params: { lectureId, title } })` (`useLocalSearchParams`로 받은 값). 대기 화면이 스냅샷을 다시 조회해 이미 OPEN이면 바로 풀이로 넘어가고, CLOSED면 토큰 발급이 409로 실패해 안내가 뜬다.
- "수업 나가기": 기존 `reset()` 앞에 `await liveLecture.leave()`.

- [ ] **Step 4: 종료·풀이 화면 나가기 경로**

- `class/end.tsx`: "오답 복습하기"·"홈으로" 두 버튼의 `reset()` 앞에 `await liveLecture.leave()` (방 삭제로 이미 끊겼으면 no-op).
- `class/play.tsx`: 수업 밖으로 나가는 경로(가드의 `router.replace("/(tabs)/class")` 등)가 있으면 그 앞에 `void liveLecture.leave()`. `/session/grade`로 가는 제출 흐름은 **그대로** 둔다(방은 유지되어 강사 음성이 계속 들려야 한다).

- [ ] **Step 5: 타입체크와 전체 테스트**

Run: `cd front && pnpm --filter @ieum/mobile typecheck && pnpm --filter @ieum/mobile exec jest`
Expected: 오류 없음, 전체 PASS

- [ ] **Step 6: Commit**

```bash
git add "front/apps/mobile/src/app/(tabs)/class.tsx" front/apps/mobile/src/app/class
git commit -m "feat(lecture): 수업 화면을 실제 입장·음성 연결 흐름으로 바꾼다"
```

---

### Task 7: 실기기 확인 (사람과 함께)

**Files:** 없음 (확인 절차). 발견한 문제는 보고서에 남긴다.

- [ ] **Step 1: 개발 빌드 재생성**

네이티브 의존성이 늘었으므로 prebuild를 다시 해야 한다(`android/`는 git 추적 밖).

```bash
cd front/apps/mobile
npx expo prebuild --clean --platform android
npx expo run:android
```

- [ ] **Step 2: 로컬 backend 준비**

`C:/Users/SSAFY/Desktop/wt-lecture-api` 에서 `docker compose up -d --build backend` (DB·Redis 포함). 앱의 `EXPO_PUBLIC_API_URL`이 에뮬레이터 기본값(`http://10.0.2.2:8080/api/v1`)이면 그대로, 실기기면 PC의 LAN IP로 설정한다(사람이 `.env`에서).

- [ ] **Step 3: E2E**

1. 강사 계정으로 수업을 만든다(웹이 없으면 curl: `POST /lectures`).
2. 앱에서 학습자 계정 로그인 → 수업 탭 → 참여 코드 입력 → 대기 화면.
3. 강사가 시작(`PUT /lectures/{id}/status` OPEN) → **앱이 풀이 화면으로 넘어간다.**
4. 강사 토큰으로 meet.livekit.io(또는 강사 웹)에서 마이크를 켠다 → **앱에서 강사 음성이 들린다.**
5. 앱의 TTS 낭독과 강사 음성이 겹칠 때의 동작(끊김·음량)을 기록한다 — 설계 §7의 위험 항목.
6. 강사가 종료(CLOSED) → **앱이 종료 화면으로 간다.**
7. 방이 있는 상태에서 비행기 모드 등으로 네트워크를 끊었다 켜면 → 연결 끊김 화면 → "다시 연결"로 복귀되는지 확인.

Expected: 3·4·6이 통과하면 MVP 완료. 5·7의 결과는 MR 본문에 기록한다.

---

## 완료 확인 (MR 전)

- [ ] `cd front && pnpm --filter @ieum/mobile exec jest` 전체 PASS
- [ ] `pnpm --filter @ieum/mobile typecheck` 통과
- [ ] `docker build --target test front` 성공 (MR verify와 동일)
- [ ] Task 7 E2E 3·4·6 확인
