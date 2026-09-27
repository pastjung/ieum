package com.itda.voice.dto;

/**
 * 합성 요청 (API 명세 12절). 검증 애너테이션을 달지 않는다 — 길이 규칙(앞뒤 공백 제거 후 1~300자)과 400 응답은 VoiceSynthesisService가
 * 맡는다. {@code @Valid} 로 검증하면 공통 처리기에서 422가 된다.
 */
public record VoiceSpeechRequest(String voiceId, String text) {}
