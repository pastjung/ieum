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

  /** openRoom마다 받은 현재 문제 id(없으면 null). openedRooms와 같은 순서다. */
  final List<Long> openedCurrentProblemIds = new ArrayList<>();

  final List<String> deletedRooms = new ArrayList<>();
  final List<MediaTokenRequest> issuedRequests = new ArrayList<>();
  final List<SpeakingChange> speakingChanges = new ArrayList<>();
  boolean failRoomOperations;

  /** true면 OpenVidu가 그 참가자를 모르는 경우(404)를 흉내 낸다. */
  boolean participantDisconnected;

  @Override
  public IssuedMediaToken issueToken(MediaTokenRequest request) {
    issuedRequests.add(request);
    return new IssuedMediaToken("wss://test", "token-" + request.identity(), Instant.EPOCH);
  }

  @Override
  public void openRoom(String roomName, Long currentProblemId) {
    failIfRequested();
    openedRooms.add(roomName);
    openedCurrentProblemIds.add(currentProblemId);
  }

  @Override
  public void deleteRoom(String roomName) {
    failIfRequested();
    deletedRooms.add(roomName);
  }

  @Override
  public boolean setSpeaking(String roomName, String identity, boolean allowed) {
    failIfRequested();
    if (participantDisconnected) {
      return false;
    }
    speakingChanges.add(new SpeakingChange(roomName, identity, allowed));
    return true;
  }

  record SpeakingChange(String roomName, String identity, boolean allowed) {}

  private void failIfRequested() {
    if (failRoomOperations) {
      throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }
  }
}
