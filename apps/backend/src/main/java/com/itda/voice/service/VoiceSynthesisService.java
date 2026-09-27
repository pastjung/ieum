package com.itda.voice.service;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.global.security.AuthenticatedUser;
import com.itda.user.domain.UserRole;
import com.itda.voice.cartesia.VoiceSynthesisException;
import com.itda.voice.cartesia.VoiceSynthesisGateway;
import com.itda.voice.repository.VoiceRateLimiter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 목소리 복제·합성 대리 호출 (API 명세 12절).
 *
 * <p>판정 순서: 역할(403) → 키 설정(503) → 입력(400) → 사용량(429) → Cartesia(502). 입력 오류는 사용량을 세지 않고, Cartesia가
 * 실패한 요청은 센다 — 외부 호출 비용은 이미 났다.
 *
 * <p>입력 검증을 Bean Validation에 맡기지 않는다. {@code @Valid} 실패는 공통 처리기에서 422가 되지만 계약은 400이고, "앞뒤 공백 제거 후
 * 1~300자"는 애너테이션으로 표현되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceSynthesisService {

  static final String DEFAULT_VOICE_NAME = "내 목소리";
  static final int MAX_VOICE_NAME_LENGTH = 50;
  static final int MAX_VOICE_ID_LENGTH = 100;
  static final int MAX_TEXT_LENGTH = 300;

  private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] WAVE = "WAVE".getBytes(StandardCharsets.US_ASCII);

  /** {@code RIFF} (4) + 크기 (4) + {@code WAVE} (4). 이보다 짧으면 WAV가 아니다. */
  private static final int WAV_HEADER_PREFIX = 12;

  private final VoiceSynthesisGateway gateway;
  private final VoiceRateLimiter rateLimiter;

  /**
   * @return 외부 서비스의 voice id
   */
  public String cloneVoice(AuthenticatedUser user, MultipartFile clip, String name) {
    requireLearner(user);
    requireAvailable();
    byte[] header = readHeader(clip);
    if (!isWav(clip, header)) {
      throw invalidInput();
    }
    String voiceName = voiceName(name);
    consumeBudget(user);
    byte[] clipBytes = readFullBytes(clip);
    try {
      return this.gateway.cloneVoice(clipBytes, voiceName);
    } catch (VoiceSynthesisException e) {
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_FAILED);
    }
  }

  /**
   * @return MP3 바이트
   */
  public byte[] synthesize(AuthenticatedUser user, String voiceId, String text) {
    requireLearner(user);
    requireAvailable();
    if (voiceId == null || voiceId.isBlank() || length(voiceId) > MAX_VOICE_ID_LENGTH) {
      throw invalidInput();
    }
    String transcript = transcript(text);
    consumeBudget(user);
    try {
      return this.gateway.synthesize(voiceId, transcript);
    } catch (VoiceSynthesisException e) {
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_FAILED);
    }
  }

  private static void requireLearner(AuthenticatedUser user) {
    if (user.role() != UserRole.LEARNER) {
      throw new BusinessException(ErrorCode.LEARNER_ACCESS_REQUIRED);
    }
  }

  private void requireAvailable() {
    if (!this.gateway.isAvailable()) {
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    }
  }

  /** 저장소 장애면 호출을 막는다(fail-closed). 막지 않으면 장애 동안 키 사용량 한도가 사라진다. */
  private void consumeBudget(AuthenticatedUser user) {
    boolean allowed;
    try {
      allowed = this.rateLimiter.tryAcquire(user.userId());
    } catch (DataAccessException e) {
      log.warn("음성 사용량 저장소 오류: {}", e.getClass().getSimpleName());
      throw new BusinessException(ErrorCode.VOICE_SYNTHESIS_UNAVAILABLE);
    }
    if (!allowed) {
      throw new BusinessException(ErrorCode.VOICE_RATE_LIMITED);
    }
  }

  /**
   * 역할·키 판정을 통과한 뒤 WAV 여부만 가리기 위해 앞쪽 {@value #WAV_HEADER_PREFIX} 바이트만 읽는다. 나머지(최대 10MB)는 사용량을 확인한
   * 뒤에야 읽는다 — 거절될 요청을 위해 전체를 메모리에 올리지 않는다.
   */
  private static byte[] readHeader(MultipartFile clip) {
    if (clip == null || clip.isEmpty()) {
      return new byte[0];
    }
    try (InputStream in = clip.getInputStream()) {
      return in.readNBytes(WAV_HEADER_PREFIX);
    } catch (IOException e) {
      throw invalidInput();
    }
  }

  private static byte[] readFullBytes(MultipartFile clip) {
    try {
      return clip.getBytes();
    } catch (IOException e) {
      throw invalidInput();
    }
  }

  private static boolean isWav(MultipartFile clip, byte[] header) {
    return clip != null
        && clip.getSize() > WAV_HEADER_PREFIX
        && header.length == WAV_HEADER_PREFIX
        && Arrays.equals(header, 0, 4, RIFF, 0, 4)
        && Arrays.equals(header, 8, 12, WAVE, 0, 4);
  }

  private static String voiceName(String name) {
    if (name == null || name.isBlank()) {
      return DEFAULT_VOICE_NAME;
    }
    String stripped = name.strip();
    if (length(stripped) > MAX_VOICE_NAME_LENGTH) {
      throw invalidInput();
    }
    return stripped;
  }

  private static String transcript(String text) {
    if (text == null) {
      throw invalidInput();
    }
    String stripped = text.strip();
    if (stripped.isEmpty() || length(stripped) > MAX_TEXT_LENGTH) {
      throw invalidInput();
    }
    return stripped;
  }

  /** 글자 수는 code point로 센다. UTF-16 길이로 세면 이모지 하나가 두 자가 된다. */
  private static int length(String value) {
    return value.codePointCount(0, value.length());
  }

  private static BusinessException invalidInput() {
    return new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
  }
}
