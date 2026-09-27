package com.itda.lecture.media;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.CanUpdateOwnMetadata;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.VideoGrant;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import livekit.LivekitModels.ParticipantPermission;
import livekit.LivekitModels.TrackSource;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

@Slf4j
public class LiveKitRoomGateway implements LiveRoomGateway {

  /** API 명세 8.5절. 접속 중 갱신은 서버가 하므로 재접속 창만 덮으면 된다. */
  private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

  /**
   * API로 만든 방에 아무도 들어오지 않을 때 서버가 정리하기까지의 시간. 참가자가 한 번 들어왔다가 모두 나가면 이 값이 아니라 LiveKit의 departure
   * timeout(기본 수십 초)으로 방이 닫혀 metadata도 사라진다 — 그래서 강사 토큰 발급 때 방을 다시 연다 (LectureMediaService). 수업 종료는
   * deleteRoom이 담당한다.
   */
  private static final int EMPTY_TIMEOUT_SECONDS = 600;

  private static final int NOT_FOUND = 404;

  /** 참가자 속성 규약 (API 명세 8.5.1절). 웹·앱이 같은 키·값을 읽는다. */
  static final String HAND_ATTRIBUTE = "hand";

  static final String HAND_DOWN = "down";
  static final String SPEAKING_ATTRIBUTE = "speaking";
  static final String SPEAKING_ALLOWED = "allowed";
  static final String SPEAKING_NONE = "none";

  private final LiveKitProperties properties;
  private final RoomServiceClient roomServiceClient;
  private final Clock clock;

  public LiveKitRoomGateway(
      LiveKitProperties properties, RoomServiceClient roomServiceClient, Clock clock) {
    this.properties = properties;
    this.roomServiceClient = roomServiceClient;
    this.clock = clock;
  }

  @Override
  public IssuedMediaToken issueToken(MediaTokenRequest request) {
    AccessToken token = new AccessToken(properties.apiKey(), properties.apiSecret());
    token.setIdentity(request.identity());
    token.setName(request.displayName());
    // TTL 대신 주입된 시계로 만료 시각을 정해 JWT exp와 응답 expiresAt을 같게 맞춘다.
    Instant expiresAt = clock.instant().plus(TOKEN_TTL);
    token.setExpiration(Date.from(expiresAt));

    List<VideoGrant> grants = new ArrayList<>();
    grants.add(new RoomJoin(true));
    grants.add(new RoomName(request.roomName()));
    grants.add(new CanSubscribe(true));
    grants.add(new CanPublishData(false));
    if (request.publisher()) {
      grants.add(new CanPublish(true));
      grants.add(new CanPublishSources(List.of("microphone")));
    } else {
      // 입장할 때는 말할 수 없다. 발언은 강사가 허용할 때 서버가 참가자 권한을 바꿔 준다 (setSpeaking).
      grants.add(new CanPublish(false));
      // 손들기 속성(hand)을 학습자가 직접 쓴다. 숨기지 않는다 — 강사 참가자 목록에 보여야 한다 (API 명세 8.5절).
      grants.add(new CanUpdateOwnMetadata(true));
    }
    token.addGrants(grants.toArray(VideoGrant[]::new));

    return new IssuedMediaToken(properties.clientUrl(), token.toJwt(), expiresAt);
  }

  @Override
  public void openRoom(String roomName, Long currentProblemId) {
    String metadata = openMetadata(currentProblemId);
    // 학습자가 SCHEDULED에서 먼저 접속하면 LiveKit이 metadata 없는 방을 자동 생성해 둔다.
    // 그 경우 createRoom은 기존 방을 돌려줄 뿐 metadata를 바꾸지 않으므로 갱신을 항상 함께 부른다.
    execute(
        roomServiceClient.createRoom(roomName, EMPTY_TIMEOUT_SECONDS, null, null, metadata),
        "createRoom",
        false);
    execute(roomServiceClient.updateRoomMetadata(roomName, metadata), "updateRoomMetadata", false);
  }

  /** 방 metadata 규약 (API 명세 8.5.2절). id는 숫자라 JSON 이스케이프가 필요 없다. 현재 문제가 없으면 키를 넣지 않는다. */
  static String openMetadata(Long currentProblemId) {
    return currentProblemId == null
        ? "{\"status\":\"OPEN\"}"
        : "{\"status\":\"OPEN\",\"currentProblemId\":\"" + currentProblemId + "\"}";
  }

  @Override
  public void deleteRoom(String roomName) {
    execute(roomServiceClient.deleteRoom(roomName), "deleteRoom", true);
  }

  @Override
  public boolean setSpeaking(String roomName, String identity, boolean allowed) {
    // 권한은 부분 갱신이 아니라 통째로 바뀐다. 구독·자기 속성 갱신(손들기)을 매번 다시 적는다.
    ParticipantPermission.Builder permission =
        ParticipantPermission.newBuilder()
            .setCanSubscribe(true)
            .setCanPublish(allowed)
            .setCanPublishData(false)
            .setCanUpdateMetadata(true);
    if (allowed) {
      permission.addCanPublishSources(TrackSource.MICROPHONE);
    }
    Map<String, String> attributes =
        Map.of(
            HAND_ATTRIBUTE,
            HAND_DOWN,
            SPEAKING_ATTRIBUTE,
            allowed ? SPEAKING_ALLOWED : SPEAKING_NONE);
    // 표시 이름·metadata는 null로 보내 바꾸지 않는다.
    return execute(
        roomServiceClient.updateParticipant(
            roomName, identity, null, null, permission.build(), attributes),
        "updateParticipant",
        true);
  }

  /**
   * @param notFoundAllowed true면 404를 실패가 아니라 "대상 없음"으로 본다
   * @return 성공이면 true, 404를 허용한 호출에서 대상이 없으면 false
   */
  private boolean execute(Call<?> call, String operation, boolean notFoundAllowed) {
    try {
      Response<?> response = call.execute();
      if (response.isSuccessful()) {
        return true;
      }
      if (notFoundAllowed && response.code() == NOT_FOUND) {
        return false;
      }
      log.warn("OpenVidu {} 실패: HTTP {}", operation, response.code());
    } catch (IOException e) {
      log.warn("OpenVidu {} 호출 실패: {}", operation, e.getClass().getSimpleName());
    }
    throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
}
