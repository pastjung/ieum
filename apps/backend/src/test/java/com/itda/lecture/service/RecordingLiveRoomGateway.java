package com.itda.lecture.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.lecture.media.LiveRoomGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 호출을 기록하는 가짜 OpenVidu. 저장소의 Recording* 테스트 대역과 같은 방식이다. */
class RecordingLiveRoomGateway implements LiveRoomGateway {

  final List<String> openedRooms = new ArrayList<>();
  final List<String> deletedRooms = new ArrayList<>();
  final List<MediaTokenRequest> issuedRequests = new ArrayList<>();
  boolean failRoomOperations;

  @Override
  public IssuedMediaToken issueToken(MediaTokenRequest request) {
    issuedRequests.add(request);
    return new IssuedMediaToken("wss://test", "token-" + request.identity(), Instant.EPOCH);
  }

  @Override
  public void openRoom(String roomName) {
    failIfRequested();
    openedRooms.add(roomName);
  }

  @Override
  public void deleteRoom(String roomName) {
    failIfRequested();
    deletedRooms.add(roomName);
  }

  private void failIfRequested() {
    if (failRoomOperations) {
      throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }
  }
}
