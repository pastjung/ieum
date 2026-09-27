package com.itda.lecture.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.itda.global.error.ErrorCode;
import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import com.itda.lecture.media.LiveRoomGateway.MediaTokenRequest;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.livekit.server.RoomServiceClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import livekit.LivekitModels.DataPacket;
import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitModels.ParticipantPermission;
import livekit.LivekitModels.Room;
import livekit.LivekitModels.TrackSource;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

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
  void 학습자_토큰은_입장_시_수신과_자기_속성_갱신만_허용한다() throws Exception {
    IssuedMediaToken issued =
        gateway.issueToken(new MediaTokenRequest("lecture-42", "user-8", "learner", false));

    Map<String, Object> video = verifiedClaims(issued.token()).getJSONObjectClaim("video");
    assertThat(video).containsEntry("canPublish", false).containsEntry("canPublishData", false);
    assertThat(video).containsEntry("canSubscribe", true);
    assertThat(video).doesNotContainKey("canPublishSources");
    // 손들기(hand 속성)를 학습자가 직접 쓴다 (API 명세 8.5.1절).
    assertThat(video).containsEntry("canUpdateOwnMetadata", true);
    // 강사가 참가자 목록에서 학습자를 보고 발언을 허용해야 하므로 숨기지 않는다 (API 명세 8.5절).
    assertThat(video.get("hidden")).isNotEqualTo(true);
  }

  @Test
  void 방_이름과_identity_규칙() {
    assertThat(LectureRoom.nameOf(42L)).isEqualTo("lecture-42");
    assertThat(LectureRoom.identityOf(7L)).isEqualTo("user-7");
  }

  // ---------- 발언 권한 (API 명세 8.5.1절) ----------

  /** RoomServiceClient는 Kotlin final 클래스지만 Mockito 5 기본 inline mock maker가 대역을 만든다. */
  private final RoomServiceClient roomClient = mock(RoomServiceClient.class);

  private final LiveKitRoomGateway speakingGateway =
      new LiveKitRoomGateway(properties, roomClient, Clock.fixed(NOW, ZoneOffset.UTC));

  @SuppressWarnings("unchecked")
  private void answer(Response<ParticipantInfo> response) throws IOException {
    Call<ParticipantInfo> call = mock(Call.class);
    given(call.execute()).willReturn(response);
    given(roomClient.updateParticipant(any(), any(), any(), any(), any(), any())).willReturn(call);
  }

  private static Response<ParticipantInfo> error(int status) {
    return Response.error(status, ResponseBody.create("", null));
  }

  @SuppressWarnings("unchecked")
  private void verifyUpdate(
      ArgumentCaptor<ParticipantPermission> permission,
      ArgumentCaptor<Map<String, String>> attributes) {
    verify(roomClient)
        .updateParticipant(
            eq("lecture-42"),
            eq("user-8"),
            isNull(),
            isNull(),
            permission.capture(),
            attributes.capture());
  }

  @Test
  @SuppressWarnings("unchecked")
  void 발언을_허용하면_마이크_송출_권한을_주고_손을_내린다() throws Exception {
    answer(Response.success(ParticipantInfo.getDefaultInstance()));

    assertThat(speakingGateway.setSpeaking("lecture-42", "user-8", true)).isTrue();

    ArgumentCaptor<ParticipantPermission> permission =
        ArgumentCaptor.forClass(ParticipantPermission.class);
    ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
    verifyUpdate(permission, attributes);
    ParticipantPermission granted = permission.getValue();
    assertThat(granted.getCanSubscribe()).isTrue();
    assertThat(granted.getCanPublish()).isTrue();
    assertThat(granted.getCanPublishSourcesList()).containsExactly(TrackSource.MICROPHONE);
    assertThat(granted.getCanPublishData()).isFalse();
    // 권한은 통째로 바뀐다. 빠뜨리면 학습자가 더는 손을 들 수 없다.
    assertThat(granted.getCanUpdateMetadata()).isTrue();
    assertThat(attributes.getValue())
        .containsExactlyInAnyOrderEntriesOf(Map.of("hand", "down", "speaking", "allowed"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void 발언을_회수하면_송출_권한을_거두고_손을_내린다() throws Exception {
    answer(Response.success(ParticipantInfo.getDefaultInstance()));

    assertThat(speakingGateway.setSpeaking("lecture-42", "user-8", false)).isTrue();

    ArgumentCaptor<ParticipantPermission> permission =
        ArgumentCaptor.forClass(ParticipantPermission.class);
    ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
    verifyUpdate(permission, attributes);
    ParticipantPermission revoked = permission.getValue();
    assertThat(revoked.getCanSubscribe()).isTrue();
    assertThat(revoked.getCanPublish()).isFalse();
    assertThat(revoked.getCanPublishSourcesList()).isEmpty();
    assertThat(revoked.getCanPublishData()).isFalse();
    assertThat(revoked.getCanUpdateMetadata()).isTrue();
    assertThat(attributes.getValue())
        .containsExactlyInAnyOrderEntriesOf(Map.of("hand", "down", "speaking", "none"));
  }

  @Test
  void 방에_그_참가자가_없으면_false다() throws Exception {
    answer(error(404));

    assertThat(speakingGateway.setSpeaking("lecture-42", "user-8", true)).isFalse();
  }

  @Test
  void 그_밖의_OpenVidu_오류는_503이다() throws Exception {
    answer(error(500));

    assertThatThrownBy(() -> speakingGateway.setSpeaking("lecture-42", "user-8", true))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }

  @Test
  @SuppressWarnings("unchecked")
  void OpenVidu에_닿지_않으면_503이다() throws Exception {
    Call<ParticipantInfo> call = mock(Call.class);
    given(call.execute()).willThrow(new IOException("connection refused"));
    given(roomClient.updateParticipant(any(), any(), any(), any(), any(), any())).willReturn(call);

    assertThatThrownBy(() -> speakingGateway.setSpeaking("lecture-42", "user-8", false))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }

  // ---------- 방 metadata (API 명세 8.5.2절) ----------

  @SuppressWarnings("unchecked")
  private static Call<Room> roomCall(Response<Room> response) throws IOException {
    Call<Room> call = mock(Call.class);
    given(call.execute()).willReturn(response);
    return call;
  }

  @Test
  void 현재_문제가_있으면_방_metadata에_문제_id를_싣는다() throws Exception {
    Call<Room> ok = roomCall(Response.success(Room.getDefaultInstance()));
    given(roomClient.createRoom(any(), any(), any(), any(), any())).willReturn(ok);
    given(roomClient.updateRoomMetadata(any(), any())).willReturn(ok);

    speakingGateway.openRoom("lecture-42", 26578L);

    String expected = "{\"status\":\"OPEN\",\"currentProblemId\":\"26578\"}";
    verify(roomClient).createRoom(eq("lecture-42"), eq(600), isNull(), isNull(), eq(expected));
    verify(roomClient).updateRoomMetadata("lecture-42", expected);
  }

  @Test
  void 현재_문제가_없으면_status만_싣는다() throws Exception {
    Call<Room> ok = roomCall(Response.success(Room.getDefaultInstance()));
    given(roomClient.createRoom(any(), any(), any(), any(), any())).willReturn(ok);
    given(roomClient.updateRoomMetadata(any(), any())).willReturn(ok);

    speakingGateway.openRoom("lecture-42", null);

    verify(roomClient).updateRoomMetadata("lecture-42", "{\"status\":\"OPEN\"}");
  }

  @Test
  void metadata_쓰기가_실패하면_503이다() throws Exception {
    Call<Room> created = roomCall(Response.success(Room.getDefaultInstance()));
    Call<Room> failed = roomCall(Response.error(500, ResponseBody.create("", null)));
    given(roomClient.createRoom(any(), any(), any(), any(), any())).willReturn(created);
    given(roomClient.updateRoomMetadata(any(), any())).willReturn(failed);

    assertThatThrownBy(() -> speakingGateway.openRoom("lecture-42", 26578L))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }

  // ---------- 제출 알림 (API 명세 8.5.2절) ----------

  @SuppressWarnings("unchecked")
  private void dataCall(Response<Void> response) throws IOException {
    Call<Void> call = mock(Call.class);
    given(call.execute()).willReturn(response);
    given(roomClient.sendData(any(), any(), any(), any(), any(), any())).willReturn(call);
  }

  @Test
  void 제출_알림은_강사_identity에게만_lecture_topic으로_보낸다() throws Exception {
    dataCall(Response.<Void>success(null));

    speakingGateway.notifyAnswerSubmitted("lecture-42", "user-7", 26578L);

    ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    verify(roomClient)
        .sendData(
            eq("lecture-42"),
            payload.capture(),
            eq(DataPacket.Kind.RELIABLE),
            eq(List.of()),
            eq(List.of("user-7")),
            eq("lecture"));
    assertThat(new String(payload.getValue(), StandardCharsets.UTF_8))
        .isEqualTo("{\"type\":\"ANSWER_SUBMITTED\",\"problemId\":\"26578\"}");
  }

  @Test
  void 제출_알림이_실패하면_503을_던진다() throws Exception {
    dataCall(Response.error(500, ResponseBody.create("", null)));

    assertThatThrownBy(() -> speakingGateway.notifyAnswerSubmitted("lecture-42", "user-7", 1L))
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
  }
}
