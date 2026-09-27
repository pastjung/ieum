package com.itda.voice.service;

import java.nio.charset.StandardCharsets;

/** 음성 테스트용 녹음 바이트. */
public final class VoiceTestClips {

  private VoiceTestClips() {}

  /** {@code RIFF....WAVE} 머리글만 갖춘 size 바이트짜리 WAV. 서버는 머리글만 확인한다. size는 13 이상. */
  public static byte[] wav(int size) {
    byte[] clip = new byte[size];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, clip, 0, 4);
    System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, clip, 8, 4);
    return clip;
  }
}
