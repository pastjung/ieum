package com.itda.voice.cartesia;

/** 목소리 복제·합성 외부 서비스. 구현은 Cartesia이며 테스트는 대역으로 바꾼다. */
public interface VoiceSynthesisGateway {

  /** API 키가 설정돼 있어 호출할 수 있는가. false면 호출하지 않고 503으로 끝낸다. */
  boolean isAvailable();

  /**
   * WAV 녹음 하나로 목소리를 복제한다.
   *
   * @return 외부 서비스의 voice id
   * @throws VoiceSynthesisException 오류 응답·시간 초과·연결 실패·id 없는 응답
   */
  String cloneVoice(byte[] wav, String name);

  /**
   * 복제한 목소리로 문장을 읽는다.
   *
   * @return MP3 바이트 (비어 있지 않다)
   * @throws VoiceSynthesisException 오류 응답·시간 초과·연결 실패·빈 결과
   */
  byte[] synthesize(String voiceId, String text);
}
