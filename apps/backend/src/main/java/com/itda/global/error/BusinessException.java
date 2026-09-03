package com.itda.global.error;

import lombok.Getter;

/** 도메인/애플리케이션 계층에서 던지는 비즈니스 예외입니다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
