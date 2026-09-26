package com.itda.lecture.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.livekit.server.RoomServiceClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveKitRoomGatewayTest {

  private static final String SECRET =
      "test-secret-at-least-32-bytes-long-000"; // gitleaks:allow (테스트 전용 서명 키)
  private static final Instant NOW = Instant.parse("2026-09-26T01:00:00Z");

  private final LiveKitProperties properties =
      new LiveKitProperties("https://openvidu.i-eum.site", "test-key", SECRET);
  private final LiveKitRoomGateway gateway =
      new LiveKitRoomGateway(
          properties,
          RoomServiceClient.createClient(
              properties.url(), properties.apiKey(), properties.apiSecret()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private JWTClaimsSet verifiedClaims(String token) throws Exception {
    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
    return jwt.getJWTClaimsSet();
  }

  @Test
  void 강사_토큰은_마이크_송출을_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-7", "teacher", true));

    JWTClaimsSet claims = verifiedClaims(issued.token());
    Map<String, Object> video = claims.getJSONObjectClaim("video");
    assertThat(claims.getSubject()).isEqualTo("user-7");
    assertThat(claims.getIssuer()).isEqualTo("test-key");
    assertThat(video).containsEntry("room", "lecture-42").containsEntry("roomJoin", true);
    assertThat(video).containsEntry("canPublish", true).containsEntry("canSubscribe", true);
    assertThat(video.get("canPublishSources")).isEqualTo(List.of("microphone"));
    assertThat(issued.serverUrl()).isEqualTo("wss://openvidu.i-eum.site");
    assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(600));
  }

  @Test
  void 학습자_토큰은_수신만_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-8", "learner", false));

    Map<String, Object> video = verifiedClaims(issued.token()).getJSONObjectClaim("video");
    assertThat(video).containsEntry("canPublish", false).containsEntry("canPublishData", false);
    assertThat(video).containsEntry("canSubscribe", true);
    assertThat(video).doesNotContainKey("canPublishSources");
  }

  @Test
  void 방_이름과_identity_규칙() {
    assertThat(LectureRoom.nameOf(42L)).isEqualTo("lecture-42");
    assertThat(LectureRoom.identityOf(7L)).isEqualTo("user-7");
  }
}
