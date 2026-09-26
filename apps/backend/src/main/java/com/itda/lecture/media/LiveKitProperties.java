package com.itda.lecture.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code itda.livekit.*}. 기본값을 두지 않고, 빠지거나 미해결 플레이스홀더면 기동 단계에서 실패한다.
 *
 * @param url 서버 API 주소 (https). 클라이언트 접속 주소는 {@link #clientUrl()}
 */
@ConfigurationProperties(prefix = "itda.livekit")
public record LiveKitProperties(String url, String apiKey, String apiSecret) {

  public LiveKitProperties {
    requireValue(url, "itda.livekit.url");
    requireValue(apiKey, "itda.livekit.api-key");
    requireValue(apiSecret, "itda.livekit.api-secret");
  }

  /** 클라이언트 SDK는 WebSocket 주소로 접속한다. */
  public String clientUrl() {
    return url.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
  }

  private static void requireValue(String value, String name) {
    if (value == null || value.isBlank() || value.startsWith("${")) {
      throw new IllegalArgumentException(name + " 설정이 필요합니다.");
    }
  }
}
