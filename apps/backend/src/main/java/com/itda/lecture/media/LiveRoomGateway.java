package com.itda.lecture.media;

import java.time.Instant;

/**
 * OpenVidu(LiveKit) 경계. 서비스는 SDK를 모르고 이 인터페이스만 본다 — 테스트에서 가짜로 바꿔 끼운다.
 *
 * <p>방 조작이 실패하면 {@code BusinessException(MEDIA_SERVER_UNAVAILABLE)}을 던진다 (API 명세 8.5절).
 */
public interface LiveRoomGateway {

  IssuedMediaToken issueToken(MediaTokenRequest request);

  /**
   * 방을 만들고(이미 있으면 그대로) metadata를 {@code {"status":"OPEN","currentProblemId":"<id>"}}로 설정한다. 현재 문제가
   * 없으면 {@code currentProblemId}를 넣지 않는다. 여러 번 불러도 결과가 같다 (API 명세 8.5·8.5.2절).
   *
   * @param currentProblemId 현재 문제의 problems.id, 없으면 null
   */
  void openRoom(String roomName, Long currentProblemId);

  /** 방을 삭제한다. 이미 없으면 성공으로 본다. 참가자는 ROOM_DELETED 사유로 연결이 끊긴다. */
  void deleteRoom(String roomName);

  /**
   * 참가자의 발언(마이크 송출) 권한을 바꾸고 손을 내린다(속성 {@code hand=down}, {@code speaking=allowed|none}). 권한을 거두면
   * OpenVidu가 그 참가자가 게시한 트랙을 내린다. 여러 번 불러도 결과가 같다 (API 명세 8.5.1절).
   *
   * @return 적용했으면 true, 방에 그 참가자가 접속해 있지 않으면 false
   */
  boolean setSpeaking(String roomName, String identity, boolean allowed);

  /**
   * @param publisher true면 마이크 음성 송출 권한(강사), false면 수신과 자기 속성 갱신만(학습자 — 발언은 {@link #setSpeaking}으로
   *     따로 허용)
   */
  record MediaTokenRequest(
      String roomName, String identity, String displayName, boolean publisher) {}

  record IssuedMediaToken(String serverUrl, String token, Instant expiresAt) {}
}
