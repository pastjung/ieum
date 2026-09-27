package com.itda.voice.cartesia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cartesia 음성 복제 API (API 명세 12절).
 *
 * <ul>
 *   <li>{@code POST /voices/clone} WAV 샘플을 올리고 voice id를 받는다
 *   <li>{@code POST /tts/bytes} 그 voice id로 문장을 읽어 MP3를 받는다
 * </ul>
 *
 * <p>요청 모양은 앱(front/apps/mobile/src/services/cartesia.ts)이 실제 호출로 확인한 값을 그대로 옮긴 것이다 — 인증 {@code
 * Authorization: Bearer}, 버전 헤더 {@code Cartesia-Version}, 모델 {@code sonic-3.6}. 응답은 MP3로 받는다: WAV로
 * 받으면 스트리밍용 헤더가 붙어(RIFF 크기 0xFFFFFFFF, data 앞 LIST 청크) 앱의 플레이어가 열지 못한다.
 */
@Slf4j
public class CartesiaVoiceGateway implements VoiceSynthesisGateway {

  static final String CLONE_PATH = "/voices/clone";
  static final String SPEECH_PATH = "/tts/bytes";
  static final String VERSION_HEADER = "Cartesia-Version";
  static final String MODEL_ID = "sonic-3.6";
  static final String LANGUAGE = "ko";
  static final String CLIP_FILENAME = "voice-sample.wav";
  static final MediaType CLIP_TYPE = MediaType.parseMediaType("audio/wav");
  static final String VOICE_DESCRIPTION = "잇다 사용자 목소리";

  private final RestClient cloneClient;
  private final RestClient speechClient;
  private final String apiKey;
  private final String apiVersion;

  /**
   * @param cloneClient 복제용. 응답 대기 시간이 합성과 달라 client를 나눈다
   * @param speechClient 합성용
   */
  public CartesiaVoiceGateway(
      RestClient cloneClient, RestClient speechClient, String apiKey, String apiVersion) {
    this.cloneClient = cloneClient;
    this.speechClient = speechClient;
    this.apiKey = apiKey;
    this.apiVersion = apiVersion;
  }

  @Override
  public boolean isAvailable() {
    return !this.apiKey.isBlank();
  }

  @Override
  public String cloneVoice(byte[] wav, String name) {
    MultipartBodyBuilder form = new MultipartBodyBuilder();
    form.part("clip", new ByteArrayResource(wav)).filename(CLIP_FILENAME).contentType(CLIP_TYPE);
    form.part("name", name);
    form.part("language", LANGUAGE);
    form.part("description", VOICE_DESCRIPTION);

    CloneResponse response =
        call(
            CLONE_PATH,
            () ->
                this.cloneClient
                    .post()
                    .uri(CLONE_PATH)
                    .headers(this::authenticate)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form.build())
                    .retrieve()
                    .body(CloneResponse.class));
    if (response == null || response.id() == null || response.id().isBlank()) {
      throw failure(CLONE_PATH, "응답에 voice id 없음");
    }
    return response.id();
  }

  @Override
  public byte[] synthesize(String voiceId, String text) {
    byte[] audio =
        call(
            SPEECH_PATH,
            () ->
                this.speechClient
                    .post()
                    .uri(SPEECH_PATH)
                    .headers(this::authenticate)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.ALL)
                    .body(SpeechRequest.of(voiceId, text))
                    .retrieve()
                    .body(byte[].class));
    if (audio == null || audio.length == 0) {
      throw failure(SPEECH_PATH, "빈 합성 결과");
    }
    return audio;
  }

  private void authenticate(HttpHeaders headers) {
    headers.setBearerAuth(this.apiKey);
    headers.set(VERSION_HEADER, this.apiVersion);
  }

  private static <T> T call(String path, Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      // e.getMessage()에는 응답 본문이 들어 있다. 상태 숫자만 남긴다.
      throw failure(path, "HTTP " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw failure(path, e.getClass().getSimpleName());
    }
  }

  private static VoiceSynthesisException failure(String path, String reason) {
    log.warn("Cartesia {} 호출 실패: {}", path, reason);
    return new VoiceSynthesisException("Cartesia " + path + " 호출 실패: " + reason);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CloneResponse(String id) {}

  /** 앱의 {@code ttsRequestBody} 와 같은 필드. Cartesia는 snake_case를 받는다. */
  record SpeechRequest(
      @JsonProperty("model_id") String modelId,
      String transcript,
      Voice voice,
      String language,
      @JsonProperty("output_format") OutputFormat outputFormat) {

    static SpeechRequest of(String voiceId, String text) {
      return new SpeechRequest(
          MODEL_ID, text, new Voice(voiceId), LANGUAGE, new OutputFormat("mp3", 44_100, 128_000));
    }
  }

  record Voice(String id) {}

  record OutputFormat(
      String container,
      @JsonProperty("sample_rate") int sampleRate,
      @JsonProperty("bit_rate") int bitRate) {}
}
