package com.itda.voice.cartesia;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code itda.cartesia.*} (API 명세 12절).
 *
 * <p>API 키만 비어 있어도 된다. 키가 없으면 Backend는 기동하고 두 음성 API가 503을 돌려준다 — 음성 기능 하나 때문에 계정·수업 전체가 뜨지 않으면 안
 * 된다. 주소·버전·제한 시간은 빠지면 기동 단계에서 실패한다.
 *
 * @param cloneTimeout 복제 응답 대기. 앱 실측으로 업로드+처리가 12~14초 걸린다
 */
@ConfigurationProperties(prefix = "itda.cartesia")
public record CartesiaProperties(
    String baseUrl,
    String apiKey,
    String apiVersion,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("60s") Duration cloneTimeout,
    @DefaultValue("20s") Duration speechTimeout) {

  public CartesiaProperties {
    requireValue(baseUrl, "itda.cartesia.base-url");
    requireValue(apiVersion, "itda.cartesia.api-version");
    apiKey = apiKey == null ? "" : apiKey.strip();
    requirePositive(connectTimeout, "itda.cartesia.connect-timeout");
    requirePositive(cloneTimeout, "itda.cartesia.clone-timeout");
    requirePositive(speechTimeout, "itda.cartesia.speech-timeout");
  }

  private static void requireValue(String value, String name) {
    if (value == null || value.isBlank() || value.startsWith("${")) {
      throw new IllegalArgumentException(name + " 설정이 필요합니다.");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " 설정은 0보다 커야 합니다.");
    }
  }
}
