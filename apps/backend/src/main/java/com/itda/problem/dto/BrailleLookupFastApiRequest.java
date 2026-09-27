package com.itda.problem.dto;

/** FastAPI {@code POST /braille-lookup} 요청. 한글 단어(띄어쓰기로 여러 단어 가능) 또는 낱자 */
public record BrailleLookupFastApiRequest(String text) {}
