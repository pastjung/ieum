package com.itda.voice.cartesia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 실제 Cartesia를 호출하지 않는다. 요청 모양은 앱(front/apps/mobile/src/services/cartesia.ts)이 보내던 값과 같아야 한다. */
class CartesiaVoiceGatewayTest {

  private static final String BASE_URL = "https://api.cartesia.test";
  private static final String API_KEY = "test-cartesia-key";
  private static final String API_VERSION = "2026-08-14";
  private static final String UPSTREAM_DETAIL = "upstream-secret-detail";
  private static final byte[] WAV = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44};

  private MockRestServiceServer server;
  private CartesiaVoiceGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    this.server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    this.gateway = new CartesiaVoiceGateway(client, client, API_KEY, API_VERSION);
  }

  /** multipart 본문에서 한 파트의 값을 꺼낸다. 파트 머리글 다음 빈 줄부터 다음 경계 앞까지다. */
  private static String partValue(String body, String partName) {
    int header = body.indexOf("name=\"" + partName + "\"");
    assertThat(header).as(partName + " 파트").isNotNegative();
    int start = body.indexOf("\r\n\r\n", header) + 4;
    int end = body.indexOf("\r\n--", start);
    return body.substring(start, end);
  }

  @Test
  void 복제는_앱과_같은_헤더와_multipart로_보내고_voice_id를_돌려준다() {
    this.server
        .expect(requestTo(BASE_URL + "/voices/clone"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
        .andExpect(header("Cartesia-Version", API_VERSION))
        .andExpect(
            request -> {
              assertThat(request.getHeaders().getContentType())
                  .satisfies(
                      type ->
                          assertThat(type.isCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                              .isTrue());
              String body =
                  ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
              assertThat(body)
                  .contains("name=\"clip\"; filename=\"voice-sample.wav\"")
                  .contains("Content-Type: audio/wav");
              assertThat(partValue(body, "name")).isEqualTo("내 목소리");
              assertThat(partValue(body, "language")).isEqualTo("ko");
              assertThat(partValue(body, "description")).isEqualTo("잇다 사용자 목소리");
            })
        .andRespond(
            withSuccess(
                "{\"id\":\"voice-123\",\"name\":\"내 목소리\",\"language\":\"ko\"}",
                MediaType.APPLICATION_JSON));

    assertThat(this.gateway.cloneVoice(WAV, "내 목소리")).isEqualTo("voice-123");
    this.server.verify();
  }

  @Test
  void 복제_응답에_id가_없으면_실패다() {
    this.server
        .expect(requestTo(BASE_URL + "/voices/clone"))
        .andRespond(withSuccess("{\"name\":\"내 목소리\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> this.gateway.cloneVoice(WAV, "내 목소리"))
        .isInstanceOf(VoiceSynthesisException.class);
  }

  @Test
  void 합성은_앱의_ttsRequestBody와_같은_JSON을_보내고_MP3_바이트를_돌려준다() {
    this.server
        .expect(requestTo(BASE_URL + "/tts/bytes"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
        .andExpect(header("Cartesia-Version", API_VERSION))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {"model_id":"sonic-3.6","transcript":"안녕하세요","voice":{"id":"voice-123"},
                     "language":"ko",
                     "output_format":{"container":"mp3","sample_rate":44100,"bit_rate":128000}}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess(MP3, MediaType.parseMediaType("audio/mpeg")));

    assertThat(this.gateway.synthesize("voice-123", "안녕하세요")).isEqualTo(MP3);
    this.server.verify();
  }

  @Test
  void 합성_결과가_비어_있으면_실패다() {
    this.server
        .expect(requestTo(BASE_URL + "/tts/bytes"))
        .andRespond(withSuccess(new byte[0], MediaType.parseMediaType("audio/mpeg")));

    assertThatThrownBy(() -> this.gateway.synthesize("voice-123", "안녕"))
        .isInstanceOf(VoiceSynthesisException.class);
  }

  @Test
  void Cartesia_오류_응답의_본문은_예외_message에_싣지_않는다() {
    this.server
        .expect(requestTo(BASE_URL + "/tts/bytes"))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .body(UPSTREAM_DETAIL)
                .contentType(MediaType.TEXT_PLAIN));

    assertThatThrownBy(() -> this.gateway.synthesize("voice-123", "안녕"))
        .isInstanceOf(VoiceSynthesisException.class)
        .hasMessageContaining("401")
        .hasMessageNotContaining(UPSTREAM_DETAIL);
  }

  @Test
  void 연결_실패나_시간_초과는_VoiceSynthesisException이다() {
    ClientHttpRequestFactory timingOut =
        (uri, httpMethod) -> {
          throw new SocketTimeoutException("Read timed out");
        };
    RestClient client = RestClient.builder().baseUrl(BASE_URL).requestFactory(timingOut).build();
    CartesiaVoiceGateway timedOut = new CartesiaVoiceGateway(client, client, API_KEY, API_VERSION);

    assertThatThrownBy(() -> timedOut.synthesize("voice-123", "안녕"))
        .isInstanceOf(VoiceSynthesisException.class);
    assertThatThrownBy(() -> timedOut.cloneVoice(WAV, "내 목소리"))
        .isInstanceOf(VoiceSynthesisException.class);
  }

  @Test
  void API_키가_비어_있으면_호출할_수_없는_상태다() {
    RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

    assertThat(new CartesiaVoiceGateway(client, client, "", API_VERSION).isAvailable()).isFalse();
    assertThat(this.gateway.isAvailable()).isTrue();
  }
}
