package com.itda.lecture.dto;

/** userId는 JSON에서 10진수 문자열이다 (API 명세 8.2절). */
public record SpeakingResponse(String userId, boolean allowed) {

  public static SpeakingResponse of(long userId, boolean allowed) {
    return new SpeakingResponse(String.valueOf(userId), allowed);
  }
}
