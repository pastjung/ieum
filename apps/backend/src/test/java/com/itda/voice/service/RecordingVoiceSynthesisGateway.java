package com.itda.voice.service;

import com.itda.voice.cartesia.VoiceSynthesisException;
import com.itda.voice.cartesia.VoiceSynthesisGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 호출을 기록하는 게이트웨이 대역. HTTP 테스트에서는 요청 스레드가 쓰고 테스트 스레드가 읽으므로 동시성 안전한 목록과 volatile 스위치를 쓴다. context를
 * 공유하므로 쓰는 쪽이 {@link #reset()} 으로 되돌린다.
 */
public class RecordingVoiceSynthesisGateway implements VoiceSynthesisGateway {

  public static final String VOICE_ID = "voice-fake-1";
  public static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44};

  /** 실패 때 예외 message에 넣는 가짜 Cartesia 원문. 응답에 이 문자열이 보이면 누출이다. */
  public static final String UPSTREAM_DETAIL = "Cartesia 401: upstream-secret-detail";

  public volatile boolean available = true;
  public volatile boolean failing;
  public final List<String> clonedNames = new CopyOnWriteArrayList<>();
  public final List<Integer> clonedClipSizes = new CopyOnWriteArrayList<>();

  /** {@code "{voiceId}/{text}"} */
  public final List<String> spoken = new CopyOnWriteArrayList<>();

  public void reset() {
    this.available = true;
    this.failing = false;
    this.clonedNames.clear();
    this.clonedClipSizes.clear();
    this.spoken.clear();
  }

  @Override
  public boolean isAvailable() {
    return this.available;
  }

  @Override
  public String cloneVoice(byte[] wav, String name) {
    if (this.failing) {
      throw new VoiceSynthesisException(UPSTREAM_DETAIL);
    }
    this.clonedNames.add(name);
    this.clonedClipSizes.add(wav.length);
    return VOICE_ID;
  }

  @Override
  public byte[] synthesize(String voiceId, String text) {
    if (this.failing) {
      throw new VoiceSynthesisException(UPSTREAM_DETAIL);
    }
    this.spoken.add(voiceId + "/" + text);
    return MP3.clone();
  }
}
