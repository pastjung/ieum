package com.itda.lecture.media;

import java.time.Instant;

/**
 * OpenVidu(LiveKit) 경계. 서비스는 SDK를 모르고 이 인터페이스만 본다 — 테스트에서 가짜로 바꿔 끼운다.
 *
 * <p>방 조작이 실패하면 {@code BusinessException(MEDIA_SERVER_UNAVAILABLE)}을 던진다 (API 명세 8.5절).
 */
public interface LiveRoomGateway {

  IssuedMediaToken issueToken(MediaTokenRequest request);

  /** 방을 만들고(이미 있으면 그대로) metadata를 OPEN으로 설정한다. 여러 번 불러도 결과가 같다. */
  void openRoom(String roomName);

  /** 방을 삭제한다. 이미 없으면 성공으로 본다. 참가자는 ROOM_DELETED 사유로 연결이 끊긴다. */
  void deleteRoom(String roomName);

  /**
   * @param publisher true면 마이크 음성 송출 권한(강사), false면 수신만(학습자)
   */
  record MediaTokenRequest(
      String roomName, String identity, String displayName, boolean publisher) {}

  record IssuedMediaToken(String serverUrl, String token, Instant expiresAt) {}
}
