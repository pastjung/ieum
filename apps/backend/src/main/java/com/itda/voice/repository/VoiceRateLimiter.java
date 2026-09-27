package com.itda.voice.repository;

/** 음성 합성 사용량. 복제·합성 두 API가 한도를 공유한다 (API 명세 12절). */
public interface VoiceRateLimiter {

  /**
   * 이번 호출을 센다.
   *
   * @return 이번 분의 한도 안이면 true
   * @throws org.springframework.dao.DataAccessException 저장소 장애
   */
  boolean tryAcquire(long userId);
}
