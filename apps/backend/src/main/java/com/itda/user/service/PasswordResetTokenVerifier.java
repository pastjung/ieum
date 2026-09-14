package com.itda.user.service;

/** 비밀번호 재설정 토큰 검증 outbound port (API 명세 3.6.1절). */
public interface PasswordResetTokenVerifier {

  ActionTokenVerification verify(String compactToken);
}
