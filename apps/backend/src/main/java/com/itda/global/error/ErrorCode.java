package com.itda.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 공통 에러 코드. API 명세서 0-3의 코드 접두 규칙을 따릅니다. 400 XXX_4xxx / 401 AUTH_4010 / 403 AUTH_4030 / 404
 * XXX_4040 / 409 XXX_4090 / 500 SYS_5000 이거는 임의이고 추가도 자유입니다!
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  // common
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_4000", "잘못된 요청입니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS_5000", "서버 내부 오류입니다."),

  // auth
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_4010", "인증이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH_4030", "권한이 없습니다."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH_4090", "이미 가입된 이메일입니다."),

  // user
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_4040", "사용자를 찾을 수 없습니다."),

  // braille
  PATTERN_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "BRAILLE_4001", "패턴 값이 허용 범위(0~63)를 벗어났습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
