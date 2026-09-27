package com.itda.voice.dto;

/** 목소리 복제 결과 (API 명세 12절). 앱이 이 id를 보관해 합성에 쓴다. */
public record VoiceCloneResponse(String voiceId) {}
