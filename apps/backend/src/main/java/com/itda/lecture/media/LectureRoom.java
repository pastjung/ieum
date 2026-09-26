package com.itda.lecture.media;

/** 수업 ↔ OpenVidu 방·참가자 식별 규칙 (API 명세 8.5절). 규칙이 흩어지면 웹·앱과 어긋난다. */
public final class LectureRoom {

  private LectureRoom() {}

  public static String nameOf(long lectureId) {
    return "lecture-" + lectureId;
  }

  public static String identityOf(long userId) {
    return "user-" + userId;
  }
}
