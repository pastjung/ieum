package com.itda.global.response;

/** 공통 응답 래퍼. API 명세서 0-3 참고. 하시면 댑니다/. 성공: { "success": true, "data": ... } */
public record ApiResponse<T>(boolean success, T data) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data);
  }
}
