package com.itda.voice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.user.domain.UserRole;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.multipart.MultipartFile;

class VoiceSynthesisServiceTest {

  private static final AuthenticatedUser LEARNER = new AuthenticatedUser(7L, UserRole.LEARNER);
  private static final byte[] CLIP_BYTES = VoiceTestClips.wav(64);

  private final RecordingVoiceSynthesisGateway gateway = new RecordingVoiceSynthesisGateway();
  private final StubVoiceRateLimiter rateLimiter = new StubVoiceRateLimiter();
  private final VoiceSynthesisService service = new VoiceSynthesisService(gateway, rateLimiter);

  private static ErrorCode errorOf(ThrowingCallable call) {
    return catchThrowableOfType(BusinessException.class, call).getErrorCode();
  }

  /** {@code content} 가 null이면 파트 자체가 없는 요청을 흉내낸다. */
  private static MultipartFile clip(byte[] content) {
    return content == null ? null : new RecordingMultipartFile(content);
  }

  // ---------- 역할·설정 ----------

  @ParameterizedTest
  @EnumSource(
      value = UserRole.class,
      names = {"INSTRUCTOR", "ADMIN"})
  void 학습자가_아니면_LEARNER_ACCESS_REQUIRED이고_사용량을_세지_않는다(UserRole role) {
    AuthenticatedUser user = new AuthenticatedUser(8L, role);

    assertThat(errorOf(() -> service.cloneVoice(user, clip(CLIP_BYTES), null)))
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertThat(errorOf(() -> service.synthesize(user, "voice-1", "안녕")))
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void 학습자가_아니면_복제_요청의_녹음_바이트를_전혀_읽지_않는다() {
    AuthenticatedUser instructor = new AuthenticatedUser(8L, UserRole.INSTRUCTOR);
    RecordingMultipartFile clip = new RecordingMultipartFile(CLIP_BYTES);

    assertThat(errorOf(() -> service.cloneVoice(instructor, clip, null)))
        .isEqualTo(ErrorCode.LEARNER_ACCESS_REQUIRED);

    assertThat(clip.getInputStreamCalls).hasValue(0);
    assertThat(clip.getBytesCalls).hasValue(0);
  }

  @Test
  void 키가_없으면_입력_검증보다_먼저_503이다() {
    gateway.available = false;

    assertThat(errorOf(() -> service.cloneVoice(LEARNER, clip(new byte[0]), null)))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertThat(errorOf(() -> service.synthesize(LEARNER, "", "")))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void 키가_없으면_복제_요청의_녹음_바이트를_전혀_읽지_않는다() {
    gateway.available = false;
    RecordingMultipartFile clip = new RecordingMultipartFile(CLIP_BYTES);

    assertThat(errorOf(() -> service.cloneVoice(LEARNER, clip, null)))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);

    assertThat(clip.getInputStreamCalls).hasValue(0);
    assertThat(clip.getBytesCalls).hasValue(0);
  }

  // ---------- 복제 입력 ----------

  @Test
  void 이름을_생략하거나_공백만_보내면_내_목소리로_복제한다() {
    assertThat(service.cloneVoice(LEARNER, clip(CLIP_BYTES), null))
        .isEqualTo(RecordingVoiceSynthesisGateway.VOICE_ID);
    service.cloneVoice(LEARNER, clip(CLIP_BYTES), "   ");

    assertThat(gateway.clonedNames).containsExactly("내 목소리", "내 목소리");
    assertThat(gateway.clonedClipSizes).containsExactly(64, 64);
    assertThat(rateLimiter.acquiredBy).containsExactly(7L, 7L);
  }

  @Test
  void 이름은_앞뒤_공백을_지우고_50자까지_받는다() {
    String fifty = "가".repeat(50);

    service.cloneVoice(LEARNER, clip(CLIP_BYTES), "  " + fifty + "  ");

    assertThat(gateway.clonedNames).containsExactly(fifty);
  }

  @Test
  void 이름이_50자를_넘으면_400이고_사용량을_세지_않는다() {
    assertThat(errorOf(() -> service.cloneVoice(LEARNER, clip(CLIP_BYTES), "가".repeat(51))))
        .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    assertThat(rateLimiter.acquiredBy).isEmpty();
    assertThat(gateway.clonedNames).isEmpty();
  }

  @Test
  void WAV_머리글이_없으면_400이다() {
    byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    byte[] rifx = VoiceTestClips.wav(64);
    rifx[3] = 'X';

    for (byte[] content :
        new byte[][] {null, new byte[0], "RIFF".getBytes(StandardCharsets.US_ASCII), mp3, rifx}) {
      assertThat(errorOf(() -> service.cloneVoice(LEARNER, clip(content), null)))
          .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void 입력_검증은_머리글만_읽고_사용량_확인_전까지_전체_바이트를_읽지_않는다() {
    RecordingMultipartFile clip = new RecordingMultipartFile(CLIP_BYTES);

    service.cloneVoice(LEARNER, clip, null);

    assertThat(clip.getInputStreamCalls).hasValue(1);
    assertThat(clip.getBytesCalls).hasValue(1);
  }

  // ---------- 합성 입력 ----------

  @Test
  void 문장은_앞뒤_공백을_지운_값으로_합성하고_300자까지_받는다() {
    String threeHundred = "가".repeat(300);

    assertThat(service.synthesize(LEARNER, "voice-1", "  안녕하세요  "))
        .isEqualTo(RecordingVoiceSynthesisGateway.MP3);
    service.synthesize(LEARNER, "voice-1", " " + threeHundred + " ");

    assertThat(gateway.spoken).containsExactly("voice-1/안녕하세요", "voice-1/" + threeHundred);
  }

  @Test
  void 문장이_비었거나_300자를_넘으면_400이고_사용량을_세지_않는다() {
    for (String text : new String[] {null, "", "   ", "가".repeat(301)}) {
      assertThat(errorOf(() -> service.synthesize(LEARNER, "voice-1", text)))
          .as("text=%s", text)
          .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    assertThat(rateLimiter.acquiredBy).isEmpty();
  }

  @Test
  void voiceId는_100자까지_받고_비었거나_넘으면_400이다() {
    service.synthesize(LEARNER, "v".repeat(100), "안녕");

    for (String voiceId : new String[] {null, "", "  ", "v".repeat(101)}) {
      assertThat(errorOf(() -> service.synthesize(LEARNER, voiceId, "안녕")))
          .as("voiceId=%s", voiceId)
          .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER);
    }
    assertThat(rateLimiter.acquiredBy).containsExactly(7L);
  }

  // ---------- 사용량·외부 실패 ----------

  @Test
  void 사용량을_넘으면_429이고_Cartesia를_부르지_않는다() {
    rateLimiter.allowing = false;

    assertThat(errorOf(() -> service.cloneVoice(LEARNER, clip(CLIP_BYTES), null)))
        .isEqualTo(ErrorCode.VOICE_RATE_LIMITED);
    assertThat(errorOf(() -> service.synthesize(LEARNER, "voice-1", "안녕")))
        .isEqualTo(ErrorCode.VOICE_RATE_LIMITED);
    assertThat(gateway.clonedNames).isEmpty();
    assertThat(gateway.spoken).isEmpty();
  }

  @Test
  void 사용량_저장소가_실패하면_503이고_Cartesia를_부르지_않는다() {
    rateLimiter.failure = new RedisConnectionFailureException("redis down");

    assertThat(errorOf(() -> service.synthesize(LEARNER, "voice-1", "안녕")))
        .isEqualTo(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    assertThat(gateway.spoken).isEmpty();
  }

  @Test
  void Cartesia_실패는_502로_바꾸고_원문을_message에_싣지_않는다() {
    gateway.failing = true;

    BusinessException clone =
        catchThrowableOfType(
            BusinessException.class, () -> service.cloneVoice(LEARNER, clip(CLIP_BYTES), null));
    BusinessException speech =
        catchThrowableOfType(
            BusinessException.class, () -> service.synthesize(LEARNER, "voice-1", "안녕"));

    assertThat(clone.getErrorCode()).isEqualTo(ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertThat(speech.getErrorCode()).isEqualTo(ErrorCode.VOICE_SYNTHESIS_FAILED);
    assertThat(speech.getMessage()).doesNotContain("upstream-secret-detail");
    assertThat(rateLimiter.acquiredBy).containsExactly(7L, 7L);
  }
}
