package com.itda.global.error;

/**
 * 공통 에러 응답. API 명세서 0-3 참고!!
 * { "success": false, "error": { "code": "...", "message": "..." } }
 */
public record ErrorResponse(boolean success, ErrorBody error) {

    public record ErrorBody(String code, String message) {}

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(false, new ErrorBody(errorCode.getCode(), errorCode.getMessage()));
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(false, new ErrorBody(code, message));
    }
}
