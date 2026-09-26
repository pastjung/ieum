# 실시간 수업 양방향 음성 설계

작성일: 2026-09-26
대상: `apps/backend`, `front/apps/web`, `front/apps/mobile`, `docs/product/api-spec.md`, `docs/product/feature-spec.md`
선행: 실시간 수업 MVP(`2026-09-26-live-lecture-mvp-design.md`) — 강사 → 학습자 단방향 음성, 코드 입장, 시작·종료

강사가 허용한 학생만 말할 수 있는 양방향 음성을 더한다. 학생은 앱에서 손을 들어 발언을 요청하고,
강사가 웹에서 발언을 허용하면 학생이 직접 마이크를 켠다. 허용된 동안 학생은 마이크를 자유롭게 켜고 끈다.
강사가 발언을 끄면 학생 마이크는 즉시 내려간다. 학생 목소리는 강사와 다른 학생 모두에게 들린다.

## 1. 결정 사항 (사용자 확정)

| 질문 | 결정 |
| --- | --- |
| 강사의 "켜기"는 무엇인가 | **발언 허용**. 학생 마이크를 원격으로 켜지 않는다. 학생이 직접 켠다. 강사의 "끄기"는 즉시 송출 권한을 회수한다 |
| 누가 듣나 | **강사와 다른 학생 모두** (교실처럼) |
| 발언 요청 | **손들기** 포함. 발언 허용 또는 회수 시 손은 자동으로 내려간다 |
| 구현 방식 | **LiveKit 방 기능 활용** — 권한은 백엔드가 LiveKit 서버 API로 바꾸고, 손들기는 참가자 속성 |

## 2. 흐름

```
학생 앱  ── 손들기 ──▶ localParticipant.setAttributes({hand: "up"})
                                   │ RoomEvent.ParticipantAttributesChanged
강사 웹  ◀─────────────────────────┘  학생 카드에 ✋, 목록 위로 정렬

강사 웹  ── 발언 허용 ──▶ PUT /api/v1/lectures/{id}/participants/{userId}/speaking {"allowed": true}
backend  ── RoomServiceClient.updateParticipant(room, identity,
               permission{canSubscribe, canPublish, canPublishSources:[MICROPHONE], canUpdateMetadata},
               attributes{hand: "down", speaking: "allowed"})
                                   │ RoomEvent.ParticipantPermissionsChanged (학생 자신)
학생 앱  ◀─────────────────────────┘  "선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요." 낭독, 마이크 버튼 활성
학생 앱  ── 마이크 켜기/끄기 ──▶ localParticipant.setMicrophoneEnabled(on)
모두     ◀── 학생 오디오 트랙 자동 구독·재생 (강사 웹과 다른 학생 앱)

강사 웹  ── 발언 끄기 ──▶ PUT .../speaking {"allowed": false}
backend  ── updateParticipant(permission{canPublish: false, ...}, attributes{hand: "down", speaking: "none"})
                                   │ LiveKit이 학생의 게시 트랙을 강제로 내린다
학생 앱  ◀─────────────────────────┘  "선생님이 발언을 마쳤어요." 낭독, 마이크 버튼 비활성
```

## 3. backend

### 3.1 학습자 토큰 변경 (API 명세 8.5절)

| 요청자 | 권한 (변경 전 → 후) |
| --- | --- |
| 강사 | 변경 없음 (마이크 송출·구독) |
| 학습자 | `hidden=true` → **제거**(강사 목록에 보여야 한다), `canUpdateOwnMetadata=true` **추가**(손들기 속성), `canPublish=false` 유지(입장 시에는 말할 수 없다), `canPublishData=false` 유지 |

- 숨김을 해제하면 학습자끼리도 서로의 표시 이름(username)을 본다. 양방향 수업에서는 서로 목소리를 듣는 것이 전제이므로 받아들인다.
  identity(`user-{userId}`)는 그대로다.
- 재접속하면 새 토큰이라 발언 허용은 초기화된다(범위 밖 참고).

### 3.2 새 API

`PUT /api/v1/lectures/{lectureId}/participants/{userId}/speaking`

- body `{"allowed": true|false}`, 성공 `200 {"userId": "…", "allowed": true|false}`
- 권한: 담당 INSTRUCTOR만. 다른 사용자는 존재를 숨기고 `404 LECTURE_NOT_FOUND`
- 상태: OPEN에서만. 그 밖은 `409 INVALID_LECTURE_STATE`
- 대상: 해당 수업의 **활성 참여자**(participants 행, left_at NULL)가 아니면 `404 LECTURE_PARTICIPANT_NOT_FOUND`(신규)
- OpenVidu에 그 참가자가 접속해 있지 않으면(`updateParticipant` 404) `404 LECTURE_PARTICIPANT_NOT_CONNECTED`(신규) — 강사 화면에서 "지금 연결돼 있지 않은 학생"으로 안내
- OpenVidu 호출 실패는 `503 MEDIA_SERVER_UNAVAILABLE`
- 멱등: 같은 값을 다시 보내도 같은 결과
- 상태를 DB에 저장하지 않는다. LiveKit 참가자 권한·속성이 유일한 상태다

구현:
- `LiveRoomGateway.setSpeaking(roomName, identity, allowed)` 추가 → `RoomServiceClient.updateParticipant(room, identity, null, null, permission, attributes)`
  - allowed: `canSubscribe=true, canPublish=true, canPublishSources=[MICROPHONE], canUpdateMetadata=true, canPublishData=false` / attributes `{hand: "down", speaking: "allowed"}`
  - revoked: 같은 permission에서 `canPublish=false`, sources 비움 / attributes `{hand: "down", speaking: "none"}`
- `LectureSpeakingService`(신규)가 권한 판정 후 게이트웨이를 부른다. `LectureService.isOwner`, `LectureRoom.nameOf/identityOf` 재사용
- `SecurityConfig` 보호 경로에 추가

### 3.3 참가자 속성 규약 (클라이언트 공통)

| 키 | 값 | 쓰는 쪽 |
| --- | --- | --- |
| `hand` | `up` / `down` | 학생(손들기·내리기), 백엔드(허용·회수 시 `down`) |
| `speaking` | `allowed` / `none` | 백엔드만 |

클라이언트는 `speaking`을 표시 용도로만 쓰고, 실제 송출 가능 여부는 LiveKit 권한(`participant.permissions.canPublish`)으로 판단한다.

## 4. 강사 웹 (03 화면)

- **학생 그리드를 실제 참가자 목록으로** 바꾼다. 원천은 LiveKit `room.remoteParticipants`(강사 자신 제외)와 이벤트
  (`ParticipantConnected/Disconnected`, `ParticipantAttributesChanged`, `ParticipantPermissionsChanged`, `IsSpeakingChanged`, `TrackMuted/Unmuted`)
  - 카드: 이름, ✋(hand=up), 발언 허용 배지, 마이크 켜짐/꺼짐, 말하는 중 강조, 버튼 "발언 허용" / "발언 끄기"
  - 정렬: 손든 학생 → 발언 허용 학생 → 나머지(이름순)
  - 답안 상태(정답·오답·점형)는 계속 목. 카드 안에서 목 부분을 구분한다
  - 참가자가 없으면 "아직 입장한 학생이 없어요"
- **학생 음성 재생**: 원격 오디오 트랙 구독 시 `track.attach()`로 재생 요소를 붙이고, 해제·페이지 이탈 시 떼어 낸다.
  브라우저 자동 재생 제한 대비로 `room.startAudio()`를 "수업 시작" 클릭(사용자 제스처) 안에서 호출하고, 막히면 "학생 소리 켜기" 버튼을 보인다
- 발언 허용·끄기 오류 안내: 404 연결 안 됨 → "지금 연결돼 있지 않은 학생이에요", 503 → 기존 문구
- `instructorRoom` 모듈 확장: 참가자 목록 구독(스냅샷 + 변경 콜백), 원격 오디오 재생 관리. 방 연결 수명 규칙(세대 번호, 해제)은 그대로

## 5. 학습자 앱 (풀이 화면)

- 버튼 두 개 (TalkBack 라벨·힌트, 상태 변화는 기존 음성 안내 함수로 낭독):
  - **손들기 / 손 내리기** — `setAttributes({hand})`. 발언이 허용된 동안에는 숨긴다
  - **마이크 켜기 / 끄기** — 권한이 있을 때만 활성. 없으면 비활성 + "선생님이 허용하면 말할 수 있어요" 힌트
- 권한 변경 처리(`ParticipantPermissionsChanged` on local): 허용 → "선생님이 말할 수 있게 했어요. 마이크 버튼을 눌러 켜세요." / 회수 → "선생님이 발언을 마쳤어요." + 마이크 끔
- 첫 마이크 켜기 전에 안드로이드 마이크 권한(RECORD_AUDIO)을 요청한다. 거부하면 "설정에서 마이크 권한을 허용해 주세요" 낭독
- **"하이 잇다" 음성 인식과 마이크 공유**: 수업 마이크를 켜는 동안 잇다(`stopItda`)를 멈추고, 끄거나 권한이 회수되면 다시 켠다(`startItda`)
- 오디오 설정: MVP의 듣기 전용 프로필(media)은 마이크 송출과 에코 제거에 맞지 않다. 발언 허용 시 통신 프로필로 바꾸고, 회수 시 media로 되돌린다.
  구현 난도가 높으면 발언 허용 여부와 관계없이 통신 프로필로 두는 것을 허용한다(실기기 결과로 결정)
- `liveLecture` 모듈 확장: 로컬 참가자 권한 변화 콜백, `raiseHand(up)`, `setMicrophone(on)`. 세대·오디오 소유권 규칙 유지. 방을 떠날 때 마이크를 끄고 잇다를 복구한다

## 6. 명세 변경

- `feature-spec.md` F-23과 `api-spec.md` §8 표 A-21: "학습자 마이크 제외" → "강사가 허용한 학습자만 마이크 송출, 발언은 모든 참가자가 듣는다. 녹음·저장·전사 없음"
- `api-spec.md` §8.5: 학습자 권한 표 갱신, 참가자 속성 규약, 새 API와 오류 코드(`LECTURE_PARTICIPANT_NOT_FOUND`, `LECTURE_PARTICIPANT_NOT_CONNECTED`)

## 7. MR 구성

| MR | 브랜치 | 선행 |
| --- | --- | --- |
| ⑤ | `feat/lecture-voice-api` — 명세 + backend | 없음 |
| ⑥ | `feat/lecture-voice-web` — 강사 웹 | ⑤, `feat/lecture-web` |
| ⑦ | `feat/lecture-voice-app` — 학습자 앱 | ⑤, `feat/lecture-app` |

상위 통합 브랜치 `feat/lecture-voice`(dev에서 분기, 이 설계서 포함). ⑥·⑦은 `feat/lecture-web`·`feat/lecture-app`이 dev에 병합된 뒤 분기한다.

## 8. 완료 기준

- backend `./gradlew test` 통과 (권한 판정·상태·게이트웨이 호출·토큰 권한 변경 테스트)
- web `pnpm --filter @ieum/web test/lint/build`, mobile `jest` + `typecheck` 통과, `docker build --target test front` 통과
- 수동 E2E(실서버·실기기):
  1. 학생 A 앱 입장 → 강사 웹 그리드에 A가 뜬다
  2. A 손들기 → 강사 화면 ✋, 목록 위로
  3. 강사 발언 허용 → A 앱 안내 낭독, 손 내려감 → A 마이크 켜기 → **강사와 학생 B가 A 목소리를 듣는다**
  4. A 마이크 끄기/켜기 자유롭게 동작
  5. 강사 발언 끄기 → A 마이크 즉시 꺼짐, 안내 낭독, 다시 켤 수 없음
  6. 발언 중 "하이 잇다"가 오작동하지 않고, 발언을 마치면 다시 동작한다

## 9. 범위 밖

- 발언 허용·손들기 상태의 영속화(재접속 시 초기화)
- 강사의 전체 음소거, 발언 시간 제한
- 학생의 강사 음소거·볼륨 조절
- 녹음·저장·전사·다시 듣기 (기존 원칙 유지)
- 명세 §8.3 WebSocket 채널
