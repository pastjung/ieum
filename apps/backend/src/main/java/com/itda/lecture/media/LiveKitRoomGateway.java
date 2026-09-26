package com.itda.lecture.media;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.Hidden;
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

  static final String OPEN_METADATA = "{\"status\":\"OPEN\"}";

  private static final int NOT_FOUND = 404;

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
      grants.add(new CanPublish(false));
      // 숨은 참가자는 다른 참가자 목록에 나타나지 않는다 — 학습자끼리 서로를 보지 않는다 (API 명세 8.2절).
      grants.add(new Hidden(true));
    }
    token.addGrants(grants.toArray(VideoGrant[]::new));

    return new IssuedMediaToken(properties.clientUrl(), token.toJwt(), expiresAt);
  }

  @Override
  public void openRoom(String roomName) {
    // 학습자가 SCHEDULED에서 먼저 접속하면 LiveKit이 metadata 없는 방을 자동 생성해 둔다.
    // 그 경우 createRoom은 기존 방을 돌려줄 뿐 metadata를 바꾸지 않으므로 갱신을 항상 함께 부른다.
    execute(
        roomServiceClient.createRoom(roomName, EMPTY_TIMEOUT_SECONDS, null, null, OPEN_METADATA),
        "createRoom",
        false);
    execute(
        roomServiceClient.updateRoomMetadata(roomName, OPEN_METADATA), "updateRoomMetadata", false);
  }

  @Override
  public void deleteRoom(String roomName) {
    execute(roomServiceClient.deleteRoom(roomName), "deleteRoom", true);
  }

  private void execute(Call<?> call, String operation, boolean notFoundIsDone) {
    try {
      Response<?> response = call.execute();
      if (response.isSuccessful() || (notFoundIsDone && response.code() == NOT_FOUND)) {
        return;
      }
      log.warn("OpenVidu {} 실패: HTTP {}", operation, response.code());
    } catch (IOException e) {
      log.warn("OpenVidu {} 호출 실패: {}", operation, e.getClass().getSimpleName());
    }
    throw new BusinessException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
}
