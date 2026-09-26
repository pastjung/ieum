package com.itda.lecture.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.livekit.server.RoomServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveKitRoomGatewayTest {

  /** 저장소에 고정 서명 key를 남기지 않도록 실행마다 CSPRNG로 만든다 (AccessTokenTestKeys와 같은 이유). */
  private static final String SECRET = randomSecret();

  private static final Instant NOW = Instant.parse("2026-09-26T01:00:00Z");

  private final LiveKitProperties properties =
      new LiveKitProperties("https://openvidu.i-eum.site", "test-key", SECRET);
  private final LiveKitRoomGateway gateway =
      new LiveKitRoomGateway(
          properties,
          RoomServiceClient.createClient(
              properties.url(), properties.apiKey(), properties.apiSecret()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private static String randomSecret() {
    byte[] value = new byte[32];
    new SecureRandom().nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

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
    // 응답 expiresAt과 JWT exp가 같은 시계에서 나와야 클라이언트가 재발급 시점을 믿을 수 있다.
    assertThat(claims.getExpirationTime().toInstant()).isEqualTo(issued.expiresAt());
    // 강사는 참가자 목록에 보여야 한다.
    assertThat(video.get("hidden")).isNotEqualTo(true);
  }

  @Test
  void 학습자_토큰은_수신만_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-8", "learner", false));

    Map<String, Object> video = verifiedClaims(issued.token()).getJSONObjectClaim("video");
    assertThat(video).containsEntry("canPublish", false).containsEntry("canPublishData", false);
    assertThat(video).containsEntry("canSubscribe", true);
    assertThat(video).doesNotContainKey("canPublishSources");
    // 학습자끼리 참가자 목록으로 서로를 보지 않는다 (API 명세 8.2절 다른 참여자 비노출).
    assertThat(video).containsEntry("hidden", true);
  }

  @Test
  void 방_이름과_identity_규칙() {
    assertThat(LectureRoom.nameOf(42L)).isEqualTo("lecture-42");
    assertThat(LectureRoom.identityOf(7L)).isEqualTo("user-7");
  }
}
