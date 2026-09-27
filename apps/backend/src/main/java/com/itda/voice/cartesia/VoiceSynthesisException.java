package com.itda.voice.cartesia;

/**
 * 외부 음성 서비스 호출 실패.
 *
 * <p>원인 예외를 붙이지 않는다. {@code RestClientResponseException} 의 message에는 Cartesia 응답 본문이 그대로 들어 있어, 누군가
 * 이 예외를 로그로 찍으면 본문이 새어 나간다.
 */
public class VoiceSynthesisException extends RuntimeException {

  public VoiceSynthesisException(String message) {
    super(message);
  }
}
